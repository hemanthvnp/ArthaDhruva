#!/usr/bin/env bash
# Run once in Azure Cloud Shell (Bash). Sets up "wake on visit" for the VM that DEPLOY.md created:
#
#   * the VM gets a managed identity that may do exactly one thing, deallocate this VM, so that
#     idle-stop.sh can turn it off when nobody is visiting;
#   * a Function app on the free consumption plan gets a managed identity that may only read this VM's
#     power state and start it, and serves the page in wake/ that does so when someone opens the link.
#
# From a checkout:   bash deploy/azure/setup-wake.sh
# Safe to run again: everything that already exists is reused.
#
# Settings (environment): RG and VM name the resource group and the VM; APP_URL is the address of the
# application if it is not the default https://app.<ip-with-dashes>.sslip.io; WAKE_ZIP_URL is where to
# fetch the function package when this script is not run from a checkout.
set -euo pipefail

RG="${RG:-artha-rg}"
VM="${VM:-artha}"
SLEEPER_ROLE="ArthaDhruva VM sleeper"
WAKER_ROLE="ArthaDhruva VM waker"

say() { printf '\n==> %s\n' "$*"; }

SUB="$(az account show --query id -o tsv)"
VM_ID="$(az vm show -g "$RG" -n "$VM" --query id -o tsv)"
LOC="$(az vm show -g "$RG" -n "$VM" --query location -o tsv)"
IP="$(az vm show -d -g "$RG" -n "$VM" --query publicIps -o tsv)"
APP_URL="${APP_URL:-https://app.${IP//./-}.sslip.io}"
SCOPE="/subscriptions/$SUB/resourceGroups/$RG"
echo "VM $VM in $RG ($LOC), application at $APP_URL"

say "1/5 Keeping the public address across restarts"
for ip_name in $(az network public-ip list -g "$RG" --query "[?ipAddress=='$IP'].name" -o tsv); do
  az network public-ip update -g "$RG" -n "$ip_name" --allocation-method Static -o none
  echo "$ip_name is static"
done

say "2/5 Two roles, each allowing one thing"
ensure_role() {  # name, description, actions as a JSON array
  if [ -n "$(az role definition list --name "$1" --scope "$SCOPE" --query "[0].roleName" -o tsv)" ]; then
    echo "role '$1' exists"
    return 0
  fi
  local file
  file="$(mktemp)"
  cat > "$file" <<EOF
{ "Name": "$1", "IsCustom": true, "Description": "$2", "Actions": $3, "NotActions": [], "AssignableScopes": ["$SCOPE"] }
EOF
  az role definition create --role-definition "$file" -o none
  rm -f "$file"
  echo "role '$1' created"
}
ensure_role "$SLEEPER_ROLE" "Deallocate a virtual machine. Given to the VM itself so it can turn itself off when idle." \
  '["Microsoft.Compute/virtualMachines/read", "Microsoft.Compute/virtualMachines/deallocate/action"]'
ensure_role "$WAKER_ROLE" "Read a virtual machine's power state and start it. Given to the wake function." \
  '["Microsoft.Compute/virtualMachines/read", "Microsoft.Compute/virtualMachines/instanceView/read", "Microsoft.Compute/virtualMachines/start/action"]'

# A role or an identity that was created seconds ago is not always visible to the assignment yet.
assign() {  # principal id, role name
  local problem=""
  for _ in $(seq 1 18); do
    if problem="$(az role assignment create --assignee-object-id "$1" --assignee-principal-type ServicePrincipal \
        --role "$2" --scope "$VM_ID" -o none 2>&1)"; then
      echo "'$2' assigned on the VM only"
      return 0
    fi
    sleep 10
  done
  echo "Could not assign the role '$2': $problem" >&2
  return 1
}

say "3/5 The VM may deallocate itself"
VM_PRINCIPAL="$(az vm identity assign -g "$RG" -n "$VM" --query systemAssignedIdentity -o tsv)"
assign "$VM_PRINCIPAL" "$SLEEPER_ROLE"

say "4/5 The Function app"
# A subscription that has never hosted a web app or a storage account does not know those services
# yet, and answers "SubscriptionNotFound" to anything that touches them.
for namespace in Microsoft.Web Microsoft.Storage; do
  if [ "$(az provider show --namespace "$namespace" --query registrationState -o tsv)" != Registered ]; then
    echo "registering $namespace with the subscription (a minute or two)"
    az provider register --namespace "$namespace" --wait
  fi
done
APP_NAME="$(az resource list -g "$RG" --resource-type Microsoft.Web/sites --query "[?starts_with(name, 'arthadhruva')].name | [0]" -o tsv)"
if [ -n "$APP_NAME" ]; then
  echo "Function app '$APP_NAME' exists"
else
  STORAGE="$(az resource list -g "$RG" --resource-type Microsoft.Storage/storageAccounts --query "[?starts_with(name, 'arthawake')].name | [0]" -o tsv)"
  if [ -z "$STORAGE" ]; then
    STORAGE="arthawake$(openssl rand -hex 4)"
    az storage account create -g "$RG" -n "$STORAGE" -l "$LOC" --sku Standard_LRS --kind StorageV2 \
      --min-tls-version TLS1_2 --allow-blob-public-access false -o none
  fi
  echo "storage account $STORAGE"
  for candidate in arthadhruva arthadhruva-app arthadhruva-risk "arthadhruva-$(openssl rand -hex 3)"; do
    available="$(az rest --method post \
      --url "https://management.azure.com/subscriptions/$SUB/providers/Microsoft.Web/checknameavailability?api-version=2022-03-01" \
      --body "{\"name\":\"$candidate\",\"type\":\"Microsoft.Web/sites\"}" --query nameAvailable -o tsv)"
    if [ "$available" = true ]; then APP_NAME="$candidate"; break; fi
  done
  [ -n "$APP_NAME" ] || { echo "No free name for the Function app." >&2; exit 1; }
  # A student subscription does not have serverless quota in every region; the page does not care
  # where it runs, so try the VM's region first and then the others.
  created=""
  for region in "$LOC" centralindia southindia southeastasia eastasia westeurope eastus; do
    for node in 22 20; do
      echo "trying $APP_NAME in $region on Node $node"
      if az functionapp create -g "$RG" -n "$APP_NAME" --storage-account "$STORAGE" \
          --consumption-plan-location "$region" --os-type Windows --runtime node --runtime-version "$node" \
          --functions-version 4 --disable-app-insights true --assign-identity '[system]' --https-only true -o none; then
        created=yes
        break 2
      fi
    done
  done
  [ -n "$created" ] || { echo "The Function app could not be created in any region (see the errors above)." >&2; exit 1; }
fi
az functionapp config appsettings set -g "$RG" -n "$APP_NAME" -o none --settings \
  "VM_RESOURCE_ID=$VM_ID" "APP_URL=$APP_URL" AzureWebJobsDisableHomepage=true WEBSITE_RUN_FROM_PACKAGE=1
FUNCTION_PRINCIPAL="$(az functionapp identity assign -g "$RG" -n "$APP_NAME" --query principalId -o tsv)"
assign "$FUNCTION_PRINCIPAL" "$WAKER_ROLE"

say "5/5 Publishing the page"
work="$(mktemp -d)"
source_dir="$(cd "$(dirname "${BASH_SOURCE[0]:-.}")" 2> /dev/null && pwd)/wake"
if [ -f "$source_dir/host.json" ]; then
  (cd "$source_dir" && zip -qr "$work/wake.zip" .)
else
  curl -fsSL "${WAKE_ZIP_URL:?not run from a checkout: set WAKE_ZIP_URL to the function package}" -o "$work/wake.zip"
fi
az functionapp deployment source config-zip -g "$RG" -n "$APP_NAME" --src "$work/wake.zip" -o none
rm -rf "$work"

HOST="$(az functionapp show -g "$RG" -n "$APP_NAME" --query defaultHostName -o tsv)"
echo "waiting for https://$HOST/ to answer"
link=""
for _ in $(seq 1 30); do
  for path in / /open; do
    if [ "$(curl -s -o /dev/null -w '%{http_code}' "https://$HOST$path" || true)" = 200 ]; then link="https://$HOST$path"; break 2; fi
  done
  sleep 10
done

echo
if [ -n "$link" ]; then
  echo "WAKE URL: $link"
else
  echo "WAKE URL: https://$HOST/   (not answering yet; it can take a few minutes after the first deployment)"
fi

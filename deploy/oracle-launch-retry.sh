#!/usr/bin/env bash
# Keeps asking Oracle for a free Ampere ARM VM until one is available.
#
# The free A1 shape is often "out of capacity"; capacity frees up at random moments. Rather than
# clicking Create by hand, run this in Oracle Cloud Shell (the >_ icon at the top right of the console).
# Cloud Shell already has the `oci` CLI, `jq` and your credentials, so nothing needs installing.
#
#   1. Save your SSH *public* key:   nano ~/artha.pub      (paste the one line, Ctrl+O, Enter, Ctrl+X)
#   2. Run:                          bash launch.sh
#
# Leave the Cloud Shell tab open (it stops after roughly 20 minutes without interaction, so press a
# key now and then). The script stops as soon as an instance is created, and refuses to create a
# second one if an instance with the same name already exists.
set -u

NAME="${NAME:-artha}"
OCPUS="${OCPUS:-1}"
MEMORY_GB="${MEMORY_GB:-6}"
BOOT_GB="${BOOT_GB:-100}"
VCN_NAME="${VCN_NAME:-artha-vcn}"
PUBKEY_FILE="${PUBKEY_FILE:-$HOME/artha.pub}"
INTERVAL="${INTERVAL:-60}"
SHAPE="VM.Standard.A1.Flex"

# In Cloud Shell the root compartment is the tenancy, and these variables are pre-set.
TENANCY="${OCI_TENANCY:?run this inside Oracle Cloud Shell (OCI_TENANCY is not set)}"

[ -s "$PUBKEY_FILE" ] || { echo "Missing $PUBKEY_FILE. Paste your SSH public key into it first (see the header)." >&2; exit 1; }
command -v jq >/dev/null || { echo "jq not found; run this in Oracle Cloud Shell." >&2; exit 1; }

say() { printf '%s  %s\n' "$(date +%H:%M:%S)" "$*"; }

say "Looking up your network, image and availability domains..."
VCN_ID=$(oci network vcn list --compartment-id "$TENANCY" --display-name "$VCN_NAME" | jq -r '.data[0].id // empty')
[ -n "$VCN_ID" ] || { echo "No VCN named '$VCN_NAME' found. Set VCN_NAME=... to the name you used." >&2; exit 1; }

# A public subnet is one that allows public IPs on its VNICs.
SUBNET_ID=$(oci network subnet list --compartment-id "$TENANCY" --vcn-id "$VCN_ID" \
  | jq -r '[.data[] | select(.["prohibit-public-ip-on-vnic"] == false)][0].id // empty')
[ -n "$SUBNET_ID" ] || { echo "No public subnet found in $VCN_NAME." >&2; exit 1; }

IMAGE_ID=$(oci compute image list --compartment-id "$TENANCY" --operating-system "Canonical Ubuntu" \
  --operating-system-version "24.04" --shape "$SHAPE" --sort-by TIMECREATED --sort-order DESC \
  | jq -r '.data[0].id // empty')
[ -n "$IMAGE_ID" ] || { echo "No Ubuntu 24.04 image found for $SHAPE in this region." >&2; exit 1; }

mapfile -t ADS < <(oci iam availability-domain list --compartment-id "$TENANCY" | jq -r '.data[].name')
say "Region ${OCI_REGION:-?}, ${#ADS[@]} availability domain(s), shape $SHAPE ${OCPUS} OCPU / ${MEMORY_GB} GB."

existing() {
  oci compute instance list --compartment-id "$TENANCY" --display-name "$NAME" \
    | jq -r '[.data[] | select(.["lifecycle-state"] != "TERMINATED" and .["lifecycle-state"] != "TERMINATING")][0].id // empty'
}

attempt=0
while true; do
  attempt=$((attempt + 1))

  found="$(existing)"
  if [ -n "$found" ]; then
    say "An instance named '$NAME' already exists ($found). Nothing more to do."
    exit 0
  fi

  # Capacity differs per availability domain and per fault domain, so rotate through all of them.
  for ad in "${ADS[@]}"; do
    for fd in "" FAULT-DOMAIN-1 FAULT-DOMAIN-2 FAULT-DOMAIN-3; do
      fd_args=()
      [ -n "$fd" ] && fd_args=(--fault-domain "$fd")

      out=$(oci compute instance launch \
        --availability-domain "$ad" --compartment-id "$TENANCY" \
        --shape "$SHAPE" --shape-config "{\"ocpus\":$OCPUS,\"memoryInGBs\":$MEMORY_GB}" \
        --image-id "$IMAGE_ID" --subnet-id "$SUBNET_ID" --assign-public-ip true \
        --display-name "$NAME" --ssh-authorized-keys-file "$PUBKEY_FILE" \
        --boot-volume-size-in-gbs "$BOOT_GB" "${fd_args[@]}" 2>&1)
      status=$?

      if [ $status -eq 0 ]; then
        id=$(printf '%s' "$out" | jq -r '.data.id')
        say "SUCCESS on attempt $attempt (${fd:-any fault domain}). Instance: $id"
        say "Waiting for it to run so I can print the public IP..."
        for _ in $(seq 1 30); do
          state=$(oci compute instance get --instance-id "$id" | jq -r '.data["lifecycle-state"]')
          [ "$state" = "RUNNING" ] && break
          sleep 10
        done
        ip=$(oci compute instance list-vnics --instance-id "$id" | jq -r '.data[0]["public-ip"] // "not assigned yet"')
        say "State: ${state:-unknown}   PUBLIC IP: $ip"
        exit 0
      fi

      if printf '%s' "$out" | grep -qiE 'out of (host )?capacity'; then
        say "attempt $attempt: no capacity (${ad##*:} ${fd:-any FD})"
      elif printf '%s' "$out" | grep -qiE 'TooManyRequests|429'; then
        say "attempt $attempt: rate limited, backing off"; sleep 120
      else
        say "attempt $attempt: unexpected error, stopping so you can read it:"
        printf '%s\n' "$out" | tail -15
        exit 1
      fi
      sleep 3
    done
  done

  say "All combinations full; sleeping ${INTERVAL}s before the next round."
  sleep "$INTERVAL"
done

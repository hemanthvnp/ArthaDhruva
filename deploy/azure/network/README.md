# Three-tier network for development (Bicep)

> **Development only. Never production, and never the production resource group.**
> The live deployment is one VM that sleeps when idle (see `DEPLOY.md`, `deploy/azure/`). Nothing here is
> used by it, and nothing here is applied to anything: this is a target topology kept as code, for a dev
> environment you build and tear down.
>
> **Status: not deployment-ready.** The Bicep compiles and lints and the Caddy image builds and validates, but
> no deployment, `what-if` or runtime test has been run. See "Validation status" for exactly what was and was
> not checked.

```
Internet --80/443--> [snet-edge  10.20.1.0/24  Caddy; a public IP only if you create one]
                          |  443, source = snet-edge only
                          v
                     [snet-app   10.20.2.0/24  nginx + backend, no public IP]  --outbound--> optional NAT gateway
                          |  5432 / 6379 / 7687, source = snet-app only
                          v
                     [snet-data  10.20.3.0/24  Postgres, Redis, Neo4j, no route to the internet]
```

| Tier | Inbound | Outbound |
|---|---|---|
| Edge | 80 and 443 (TCP) from the internet; SSH from one operator `/32`, only if `adminSourceIp` is valid | anything |
| App | 443 from the edge subnet; SSH from the edge subnet | NAT gateway if enabled, otherwise Azure's default outbound access |
| Data | database ports from the app subnet only. No SSH from anywhere | denied to the internet |

Every NSG ends in an explicit deny-all inbound. The data subnet is private (`defaultOutboundAccess: false`).
The app subnet is private only when the NAT gateway is enabled.

## Keeping it distinct from production (naming is not a security boundary)

Nothing in this template can stop it being deployed to the wrong place. Resource names, tags and the
resource group name are labels that help a human notice a mistake; they do not enforce anything.

- **Dedicated resource group.** Deploy only into `artha-dev-net-rg`. The production resources live in
  `artha-rg`; this template does not reference them.
- **Labels in the template.** `envName` accepts only `dev`, every resource name carries it
  (`artha-dev-vnet`, `artha-dev-edge-nsg`, ...), and every resource is tagged `environment=dev`,
  `purpose=dev-network`.
- **No guard.** Bicep cannot refuse a resource group or a subscription (a parameter restricted to the dev
  group's name is a type error against `resourceGroup().name`; that was tried and removed). What stands
  between you and the wrong group is the command you type and the checks below. Consider a `CanNotDelete`
  lock on the production group, and a separate dev subscription, which is a stronger boundary than a
  resource group.
- **Separate from the production deployment.** `Caddyfile.wildcard` and `Dockerfile.caddy` are used by no
  Compose file or deploy script, and must not be added to them. Production keeps `deploy/Caddyfile` and the
  stock `caddy` image. Use a dev domain, never the production hostname, for `APP_BASE_DOMAIN`.
- **No overlap check.** The default `10.20.0.0/16` was not compared with any existing network. Check before
  peering this VNet with another.

## Before you deploy: preflight

The Azure CLI is not installed by this repo, and none of these commands has been run. Run everything from this
directory, and put `--subscription` and the resource group on every command.

```bash
cd deploy/azure/network
bicep build main.bicep --stdout > /dev/null && bicep lint main.bicep    # local, needs no Azure login

az login
az account show --query "{name:name, id:id, tenant:tenantId}" -o table
```

**Stop and read the output.** Confirm the subscription is the one you intend to create **dev** resources in. If
it is not, run `az account set --subscription <dev-subscription-id>` and `az account show` again. Then pin the
confirmed subscription id, and use it explicitly in every command so a later change of the CLI default cannot
redirect them:

```bash
SUB=<the dev subscription id you just confirmed>
```

## Deploy

```bash
az group create -n artha-dev-net-rg -l centralindia --tags environment=dev --subscription "$SUB"
az deployment group what-if -g artha-dev-net-rg --subscription "$SUB" -f main.bicep -p adminSourceIp=<your.ip>/32
az deployment group create  -g artha-dev-net-rg --subscription "$SUB" -f main.bicep -p adminSourceIp=<your.ip>/32
# optional, billed, decide before the first deployment (see below): -p enableNatGateway=true createEdgePublicIp=true
az deployment group show -g artha-dev-net-rg --subscription "$SUB" -n main --query properties.outputs
```

Read the `what-if` output before running `create`. The outputs give the subnet ids, the edge and NAT
addresses when you created them (empty otherwise), `sshIngressEnabled` and `sshAcceptedSource`.

### SSH source validation

`adminSourceIp` is accepted only as a single IPv4 host, `a.b.c.d/32`: four decimal octets 0 to 255 without
leading zeros, then `/32`, and not in `0.0.0.0/8`, `127.0.0.0/8`, `169.254.0.0/16` (link-local, which includes
the metadata service) or with a first octet from 224 to 255 (multicast, reserved, broadcast). Anything else
(empty, `*`, `Internet`, `0.0.0.0/0`, a range, a list, a hostname, IPv6) creates **no** SSH rule: the template
fails closed. It does **not** reject the value, so a mistyped address deploys successfully with SSH off. By
default SSH is off.

- **It is not an IP-suitability check.** Bicep has no regular expressions, so this is syntax plus those address
  blocks. Private (`10.0.0.1/32`), shared (`100.64.0.1/32`), documentation (`192.0.2.1/32`) and public
  addresses that are not yours all pass, and a well-formed wrong address silently opens SSH to that host.
  Read the address back before relying on it.
- **The outputs report the template's decision, not Azure's state.** `sshIngressEnabled` is true when the
  address passed the check and so the template asks Azure for the rule; `sshAcceptedSource` echoes the value
  that passed (empty otherwise). Neither proves the rule exists in Azure, that a VM or an edge public IP
  exists, that anything listens on port 22, or that the address is yours. To read the rule back from Azure
  (not run): `az network nsg rule show -g artha-dev-net-rg --subscription "$SUB" --nsg-name artha-dev-edge-nsg
  -n allow-ssh-from-operator --query sourceAddressPrefix -o tsv`.
- **Tested locally, not by Azure.** The expression compiles and lints, and a Python port of the same logic was
  run on 46 inputs (13 that must pass, 33 that must not). The Bicep expression itself has not been evaluated by
  Azure or by any local evaluator, and it uses only functions that cannot error on any input (no indexing, no
  text-to-number conversion).

## Optional, billed resources

Both are off by default. Everything else here (VNet, subnets, NSGs) is free.

| Resource | Parameter | Dev needs it when | Cost driver |
|---|---|---|---|
| NAT gateway + static public IP | `enableNatGateway=true` | the app tier must call out from one stable, allow-listable address (Groq, tenant webhooks). Otherwise the app subnet uses Azure's default outbound access: free, but a Microsoft-owned address that can change | hourly, whether or not anything runs; the original estimate in this repo was about $32 a month. Check current Azure pricing |
| Edge static public IP | `createEdgePublicIp=true` | you want to reach the dev edge from the internet, for example to try wildcard DNS and the real certificate. Not needed for private testing | hourly while it exists |

The larger cost is what this template does **not** create: the VMs and their disks. Compute stops billing
when a VM is deallocated, disks and static IPs do not. Estimate those from the VM sizes you choose; none of the
figures above were checked against current pricing.

### Decide the NAT option before the first deployment

- **`defaultOutboundAccess` cannot be changed after a subnet exists.** Microsoft's reference for API
  `2023-11-01` says it "can only be set at the time of subnet creation and cannot be updated for an existing
  subnet". The template sets it on the app subnet only when NAT is on. Flipping `enableNatGateway` on an
  existing deployment can therefore fail, or need the subnet recreated, which fails while NICs are attached to
  it. (From Microsoft's documentation; not tested here.)
- **Turning it off does not delete anything.** Deployments are incremental: a redeploy with
  `enableNatGateway=false` leaves `artha-dev-nat` and `artha-dev-nat-ip` in place, **still billing**. Resources
  are never removed because a parameter changed.
- **Practical rule:** pick NAT or no NAT up front. To change your mind, delete the whole dev resource group and
  deploy again (see Cleanup). The edge public IP has no such constraint, but deleting it separately
  (`artha-dev-edge-ip`) first needs it detached from any NIC.
- **Outbound default and API version.** The edge subnet, and the app subnet without NAT, leave
  `defaultOutboundAccess` unset, which means Azure's default outbound access under API `2023-11-01`. Microsoft's
  documentation says new virtual networks created with an API version released after 31 March 2026 default to
  private subnets, so moving this template to a newer API version could silently remove the edge's outbound
  access (needed for ACME and Azure DNS calls). Re-check before changing the API version.

## Cleanup and rollback

Deleting the resource group removes **everything in it**, including any VMs, disks and public IPs you created
there later. Check what it is before you delete it, and that it is the dev group:

```bash
az group show -n artha-dev-net-rg --subscription "$SUB" --query "{name:name, tags:tags}" -o json
az resource list -g artha-dev-net-rg --subscription "$SUB" -o table      # read this list

[ "$(az group show -n artha-dev-net-rg --subscription "$SUB" --query tags.environment -o tsv)" = "dev" ] \
  && az group delete -n artha-dev-net-rg --subscription "$SUB" --yes
az group exists -n artha-dev-net-rg --subscription "$SUB"                # false once it is gone
```

The tag check only catches a mistyped group name. A tag is a label, not a boundary: whoever can tag a group
can tag the wrong one. There is no partial rollback for the NAT option (above); the supported way back to a
clean state is delete and redeploy.

## What this does not create

Deploying this gives you a VNet, subnets and NSGs. **The Caddy-to-nginx path does not work from that alone.**
These are missing and are yours to provide:

- **Virtual machines**, at least an edge host (Caddy), an app host (nginx and the backend) and a data host.
  Sizes, images, disks and admin access are not defined.
- **NICs and the edge public IP binding.** The NSGs are bound to the subnets, but no NIC exists and the
  optional edge IP is not attached to anything.
- **A managed identity on the edge VM** and a **role assignment**: `DNS Zone Contributor` scoped to the dev DNS
  zone only. Without them Caddy cannot complete the DNS-01 challenge for the wildcard certificate.
- **A DNS zone hosted in Azure DNS** for the dev domain, with a wildcard `A` record (`*.<dev domain>`)
  pointing at the edge address. The Azure DNS module cannot manage zones hosted elsewhere.
- **The multi-host application configuration.** The Compose files describe one host with Docker networks.
  Splitting nginx, the backend and the databases across hosts needs, and the repo does not have:
  - an nginx publication on the app host's private address (the base file publishes it on `127.0.0.1`
    only, and `docker-compose.deploy.yml` removes the publication);
  - a backend reachable from nginx across hosts (`BACKEND_HOST`/`BACKEND_PORT`);
  - Postgres, Redis and Neo4j addresses and TLS settings for a backend on another host;
  - the `secrets/` files on each host that needs them (they reach containers as Docker secrets, per host);
  - `APP_BASE_DOMAIN` set for the backend, plus a check of `APP_PUBLIC_URL`, `FRONTEND_URL` and
    `CORS_ALLOWED_ORIGINS` for several hostnames (`deploy/.env.example`);
  - a way to get images and packages onto the data host, which has no internet route by design (bake them
    in, or open egress temporarily).
- **A management path to the data subnet** (next section).
- **The custom Caddy image** (`Dockerfile.caddy`), built and distributed to the edge host.

## Managing the data subnet: there is no path today

**What the template does (read from `main.bicep`, compiled):** the data NSG admits only 5432, 6379 and 7687
from the app subnet and denies all other inbound; the app NSG admits SSH only from the edge subnet; the data
NSG denies outbound to the internet. So **nothing can SSH to a data host, not even the app host**, and a data
host cannot reach package mirrors or image registries.

**Not done, on purpose.** I did not add an SSH rule from the app subnet: it would let a compromised app host
open SSH sessions on the databases' hosts, which is exactly the lateral movement the tiers exist to prevent.
SSH from the internet to the data tier is never an option. The NSG rules and outbound restrictions are
unchanged.

Options, neither of which is in the template:

- **VM Run Command (recommended for occasional administration).** Microsoft documents that it "uses the virtual
  machine (VM) agent to run scripts within an Azure Windows or Linux VM". That suggests it needs no inbound
  port, public IP or NSG change, but that is my inference: the page does not state its network requirements.
  **Untested here:** whether the VM agent works with the data NSG's outbound internet deny in place. Try it in a
  throwaway deployment before relying on it.
- **Azure Bastion (if you need an interactive session).** Documented requirements: a dedicated subnet named
  `AzureBastionSubnet` of /26 or larger that holds nothing else (not needed for the Developer SKU), and a
  Standard, static public IP (not needed for the Developer or private-only SKUs). The VNet's `/16` has room for
  the subnet. Bastion is a billed resource, price not checked. Reaching a VM through it still needs the target
  subnet's NSG to admit SSH from the Bastion subnet, which is an NSG change to weigh the same way as above
  (that last point is my inference from how NSGs work, not something tested).

Neither option has been deployed or tested against this template.

## The Caddy edge

`Caddyfile.wildcard` terminates TLS for `*.<APP_BASE_DOMAIN>` and the apex with one wildcard certificate
and forwards to nginx, passing the original `Host` through (the backend takes the tenant from it).

Build the image (stock Caddy has no Azure DNS module; the module and Caddy versions are pinned in the file):

```bash
docker build -f Dockerfile.caddy -t arthadhruva-caddy-azure:dev .
```

### Variables

Supplied to the container at run time. None are committed, and there are no secrets among them
(authentication is the VM's managed identity). The launch procedure below accepts only these forms:

| Variable | Required | Accepted |
|---|---|---|
| `APP_BASE_DOMAIN` | yes | one lowercase DNS name with two or more labels, at most 253 characters, for example `dev.example.test`; the same value the backend gets (`app.base-domain`). No wildcard, list, space, quote, comma or trailing dot |
| `APP_PRIVATE_IP` | yes | exactly one dotted-decimal IPv4 address: four octets 0-255, no leading zeros, inside private space (10/8, 172.16/12, 192.168/16). No hostname, CIDR, port or extra token. The nginx host in `snet-app` |
| `AZURE_SUBSCRIPTION_ID` | yes | a GUID: the subscription holding the DNS zone |
| `AZURE_DNS_RESOURCE_GROUP` | yes | 1-90 characters of letters, digits, `_`, `.`, `-`, not ending with `.`: the DNS zone's resource group, not the network's. Names with parentheses or non-ASCII characters are refused by the script even though Azure allows them |
| `APP_PORT` | no | one decimal port 1-65535, default 443; must match `appPort` in the Bicep |
| `ACME_CA` | no | an `https://` URL (host, optional port and path; no credentials, query or fragment). **Defaults to Let's Encrypt staging**; set but empty is refused |

The nginx in this repo trusts `X-Forwarded-For` only from private ranges (10/8, 172.16/12, 192.168/16), so
keep `vnetCidr` inside one of them. The script cannot know that the address is really the nginx host, that the
DNS zone exists, or that the identity has the role: it checks formats, not reachability.

### Why `caddy validate` is not enough

Caddy substitutes `{$VAR}` as text **before** parsing the Caddyfile, so the file cannot enforce its own inputs.
Observed with `caddy adapt` on the local image: `APP_PORT='443 https://x.example'` or
`APP_PRIVATE_IP='10.20.2.4 https://x.example'` gives **two upstreams**; a space-separated `APP_BASE_DOMAIN` adds a
**second site address**; an **empty** `APP_PRIVATE_IP` gives the upstream `:443` (the `.invalid` fallback covers
only an unset variable); unset or empty Azure variables adapt and validate with the keys silently absent;
`APP_PORT=0` and `ACME_CA=http://...` are accepted. All of these pass `caddy validate`. Start Caddy only with the
procedure below, which checks every value and then checks the adapted configuration.

### Launch Caddy (fail-closed; EXAMPLE, not executed against a real edge host)

Put the variables in an env file **outside the repo**, for example `~/caddy-dev.env`. Format: one `NAME=value`
per line with printable ASCII and no spaces or quotes; blank lines and `#` comments (a `#` in column 1) are fine;
LF or CRLF (one trailing CR per line is removed); no BOM; no name other than the six above; no name twice. The
procedure never sources the file and never passes it to Docker: it passes the validated values as explicit `-e`
flags, so a CRLF, a hidden character or a duplicate cannot reach the container.

Run it from this directory, on a host with Bash 4 or later, a Docker that supports `--pull never` (20.10 or
later, from memory: not checked on a real edge host) and the image built. It exits nonzero and starts
nothing if any check fails, and its error messages name the problem without printing a value.

```bash
ENVFILE=~/caddy-dev.env bash <<'LAUNCH'
#!/usr/bin/env bash
# Fail-closed launcher for the DEV Caddy edge. EXAMPLE ONLY: not run against a real edge host.
# Every value is validated BEFORE docker run; any failure exits nonzero and starts nothing. The env file is
# parsed here, never sourced and never handed to Docker: only the validated values are passed, with -e.
set -euo pipefail
export LC_ALL=C
ENVFILE=${ENVFILE:-$HOME/caddy-dev.env}
IMAGE=arthadhruva-caddy-azure:dev
STAGING_CA=https://acme-staging-v02.api.letsencrypt.org/directory
fail() { echo "launch refused: $*" >&2; exit 1; }

[ -f ./Caddyfile.wildcard ] || fail "run this from deploy/azure/network (Caddyfile.wildcard not found)"
CADDYFILE=$(pwd -P)/Caddyfile.wildcard
[ -f "$ENVFILE" ] && [ -r "$ENVFILE" ] || fail "env file not found or not readable: $ENVFILE"
command -v docker >/dev/null || fail "docker not found"
docker image inspect "$IMAGE" >/dev/null 2>&1 || fail "image $IMAGE is not built locally (this script never pulls)"

# 1. Parse: NAME=value per line, printable ASCII with no spaces; known names only; each at most once.
declare -A seen=() val=()
n=0
while IFS= read -r line || [ -n "$line" ]; do
  n=$((n + 1))
  line=${line%$'\r'}                       # one trailing CR (CRLF files); any other CR fails the check below
  if [ -z "$line" ]; then continue; fi
  case $line in '#'*) continue ;; esac
  [ "${#line}" -le 300 ] || fail "line $n is longer than 300 characters"
  [[ $line =~ ^([A-Z_]+)=([!-~]*)$ ]] \
    || fail "line $n is not NAME=value in printable ASCII (no spaces, tabs, BOM or control characters)"
  name=${BASH_REMATCH[1]}; value=${BASH_REMATCH[2]}
  case " APP_BASE_DOMAIN APP_PRIVATE_IP APP_PORT ACME_CA AZURE_SUBSCRIPTION_ID AZURE_DNS_RESOURCE_GROUP " in
    *" $name "*) ;;
    *) fail "line $n: unexpected variable name $name" ;;
  esac
  [ -z "${seen[$name]+x}" ] || fail "line $n: $name is set more than once"
  seen[$name]=1; val[$name]=$value
done < "$ENVFILE"
for v in APP_BASE_DOMAIN APP_PRIVATE_IP AZURE_SUBSCRIPTION_ID AZURE_DNS_RESOURCE_GROUP; do
  [ -n "${seen[$v]+x}" ] || fail "$v is missing from the env file"
done

# 2. Validate each value.
domain=${val[APP_BASE_DOMAIN]}
label='[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?'
{ [[ $domain =~ ^(${label}\.)+[a-z][a-z0-9-]{0,61}[a-z0-9]$ ]] && [ "${#domain}" -le 253 ]; } \
  || fail "APP_BASE_DOMAIN must be one lowercase DNS name with two or more labels, e.g. dev.example.test"

ip=${val[APP_PRIVATE_IP]}
octet='(0|[1-9][0-9]{0,2})'
[[ $ip =~ ^${octet}\.${octet}\.${octet}\.${octet}$ ]] \
  || fail "APP_PRIVATE_IP must be exactly one IPv4 address in dotted-decimal form"
o1=${BASH_REMATCH[1]}; o2=${BASH_REMATCH[2]}; o3=${BASH_REMATCH[3]}; o4=${BASH_REMATCH[4]}
for o in "$o1" "$o2" "$o3" "$o4"; do [ "$o" -le 255 ] || fail "APP_PRIVATE_IP has an octet above 255"; done
if [ "$o1" -eq 10 ] || { [ "$o1" -eq 172 ] && [ "$o2" -ge 16 ] && [ "$o2" -le 31 ]; } \
   || { [ "$o1" -eq 192 ] && [ "$o2" -eq 168 ]; }; then :; else
  fail "APP_PRIVATE_IP must be a private (RFC 1918) address"
fi

port=443
if [ -n "${seen[APP_PORT]+x}" ]; then
  port=${val[APP_PORT]}
  { [[ $port =~ ^[1-9][0-9]{0,4}$ ]] && [ "$port" -le 65535 ]; } || fail "APP_PORT must be one decimal port, 1-65535"
fi

ca=$STAGING_CA; ca_supplied=0
if [ -n "${seen[ACME_CA]+x}" ]; then
  ca=${val[ACME_CA]}; ca_supplied=1
  [[ $ca =~ ^https://[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?(:[0-9]{1,5})?(/[A-Za-z0-9._~/-]{0,200})?$ ]] \
    || fail "ACME_CA must be an https:// URL (host, optional port and path; no credentials, query or fragment)"
fi

sub=${val[AZURE_SUBSCRIPTION_ID]}
[[ $sub =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]] \
  || fail "AZURE_SUBSCRIPTION_ID must be a GUID"
rg=${val[AZURE_DNS_RESOURCE_GROUP]}
{ [[ $rg =~ ^[A-Za-z0-9_.-]{1,90}$ ]] && [ "${rg%.}" = "$rg" ]; } \
  || fail "AZURE_DNS_RESOURCE_GROUP must be 1-90 of letters, digits, _ . - and not end with a period"

# 3. Only the validated values go to Docker, as explicit -e flags.
envargs=(-e "APP_BASE_DOMAIN=$domain" -e "APP_PRIVATE_IP=$ip" -e "APP_PORT=$port"
         -e "AZURE_SUBSCRIPTION_ID=$sub" -e "AZURE_DNS_RESOURCE_GROUP=$rg")
if [ "$ca_supplied" -eq 1 ]; then envargs+=(-e "ACME_CA=$ca"); fi

# 4. Check what Caddy makes of them, in a throwaway container with no network: exactly one site address
#    list, one upstream, the intended CA and the preserved Host header. caddy validate alone proves none of this.
adapted=$(docker run --rm --pull never --network none --read-only --tmpfs /tmp "${envargs[@]}" \
  -v "$CADDYFILE:/etc/caddy/Caddyfile:ro" "$IMAGE" \
  caddy adapt --config /etc/caddy/Caddyfile --adapter caddyfile 2>&1) \
  || fail "Caddy cannot adapt the configuration with these values"
expect() {  # $1 = grep pattern, $2 = the one line it must match, $3 = label (values are never echoed)
  local found; found=$(printf '%s' "$adapted" | grep -o -- "$1" || true)
  [ "$found" = "$2" ] || fail "adapted configuration: expected exactly one correct $3"
}
expect '"host":\[[^]]*\]' "\"host\":[\"*.$domain\",\"$domain\"]" "site address list"
expect '"dial":"[^"]*"' "\"dial\":\"$ip:$port\"" "upstream"
expect '"ca":"[^"]*"' "\"ca\":\"$ca\"" "certificate authority"
expect '"Host":\["[^"]*"\]' '"Host":["{http.request.hostport}"]' "Host header setting"
expect '"subscription_id":"[^"]*"' "\"subscription_id\":\"$sub\"" "subscription_id"
expect '"resource_group_name":"[^"]*"' "\"resource_group_name\":\"$rg\"" "resource_group_name"

echo "validated: base domain $domain, upstream $ip:$port"
if [ "$ca_supplied" -eq 1 ]; then echo "certificate authority: $ca"
else echo "certificate authority: Let's Encrypt STAGING (default)"; fi
case $ca in https://acme-v02.api.letsencrypt.org/*) echo "NOTE: production CA, rate limits apply" ;; esac

# 5. Only now start Caddy.
exec docker run -d --name caddy-dev --restart unless-stopped --pull never \
  "${envargs[@]}" -p 80:80 -p 443:443 \
  -v caddy_dev_data:/data -v caddy_dev_config:/config \
  -v "$CADDYFILE:/etc/caddy/Caddyfile:ro" \
  --read-only --tmpfs /tmp \
  --cap-drop ALL --cap-add NET_BIND_SERVICE --security-opt no-new-privileges:true \
  "$IMAGE"
LAUNCH
```

What it does, in order: refuses unless the image exists locally (it never pulls, so a same-named image from a
registry cannot be substituted); parses the file strictly; validates each value as in the table; adapts the
Caddyfile with those values in a throwaway container with no network and checks that the result has exactly the
intended site address list, one upstream, the intended CA (staging by default), the preserved `Host` header
and both Azure settings; and only then starts Caddy.

It validates formats. It does not prove reachability, certificate issuance or that the upstream is nginx, and
the adapt check is configuration, not live traffic. The launch has been exercised offline only, against a
stand-in for `docker` that records instead of starting a container (see "Validation status").

### Persistent storage, and Let's Encrypt staging

- **Keep `/data` across restarts.** It holds the certificates and the ACME account (the production Compose
  overlay says the same of its `caddy_data` volume). Without a persistent volume every new container asks for a
  fresh certificate, and Let's Encrypt rate-limits repeated requests for the same names (see their rate-limit
  documentation for the current limits; none are quoted here). Use a named volume or a host directory, and a
  volume for `/config` too.
- **Start with staging.** `ACME_CA` defaults to `https://acme-staging-v02.api.letsencrypt.org/directory`.
  Let's Encrypt's staging environment exists for testing and has its own, more generous limits (their
  documentation has the numbers; none are quoted or were checked here), so you can get the DNS zone, the
  managed identity and the role assignment right without risking the production limits. Staging certificates are not trusted by browsers, so test
  with `curl -k`, or by importing the staging root. Once a certificate is issued under staging, set
  `ACME_CA=https://acme-v02.api.letsencrypt.org/directory` and restart Caddy.

### The container the launch starts

The hardening flags mirror the production Compose overlay's `caddy` service (`cap_drop: ALL`,
`NET_BIND_SERVICE`, `no-new-privileges`, read-only root, `/tmp` tmpfs), with `/data` and `/config` on named
volumes. Offline `caddy validate` of this file passed with those flags, and the image's `caddy` binary carries
`cap_net_bind_service`; the container has never been run as a service. Only TCP 80 and 443 are published,
matching the edge NSG, which allows TCP only: Caddy also offers HTTP/3 on UDP 443, so clients fall back to
HTTP/2 over TCP.

### Managed identity from a container

The Azure DNS module authenticates with the VM's managed identity (it is used when `tenant_id`, `client_id`
and `client_secret` are omitted, which is how `Caddyfile.wildcard` is written). The identity's token comes from
the instance metadata service at `169.254.169.254`, so the Caddy container must be able to reach it.

- **Do not reuse `deploy/azure/artha-imds-guard.service` on this host.** The production VM installs that unit to
  block containers from the metadata service; on the edge host it would stop Caddy getting a token.
- **The trade-off.** Anything that compromises the Caddy container can ask for the same token. Give the identity
  **only** `DNS Zone Contributor`, scoped to the dev zone itself (not the resource group or subscription), so
  the most it can do is edit that zone's records.

### Upstream TLS: encrypted, not authenticated

nginx's certificate is the throwaway one from `nginx/selfsigned-cert.sh`: CN=localhost, no SANs, written to a
tmpfs and regenerated at every start. There is no CA anywhere in the repo, so the Caddy-to-nginx hop uses
`tls_insecure_skip_verify`: the traffic is encrypted, but **the upstream certificate is not authenticated**, so
it does not protect against someone who can impersonate the nginx host. It is protected only by the app
subnet's NSG. That is the same setting as the single-server deployment, but not the same justification (there,
nothing but Caddy can reach nginx). It is a documented temporary compromise for development, not a production
configuration.

Verifying the upstream would need all of these, none of which exist yet: a CA (or a stable certificate used
as its own trust anchor); an nginx certificate whose SAN matches the address or name Caddy dials, mounted
over the tmpfs at `/etc/nginx/certs` (the script only generates one if `tls.crt` is absent); a rotation
process; and the CA certificate distributed to the Caddy host.

## Image scanning

The Security workflow (`.github/workflows/security.yml`) runs `trivy config --severity MEDIUM,HIGH,CRITICAL`
over the whole repository and uploads the SARIF to code scanning. There is no `--exit-code`, no Trivy ignore or
config file, and nothing in this change adds one: no scan was weakened or bypassed.

`Dockerfile.caddy`, like the stock Caddy image and the production Compose `caddy` service, runs as root and
has no `USER`; the repo's own `Dockerfile` does set `USER app`. Trivy is therefore **likely** to report a
missing non-root user for it. **Unverified:** Trivy is not installed here and its image was not pulled, so the
finding has not been reproduced. A non-root build would need its file capability for ports 80 and 443
handled and a run test, which this change does not attempt. The compensating control is the run-time
hardening above.

## Validation status

Not deployment-ready. Three kinds of statement, kept apart:

**Verified here by running it (local, offline, no Azure account):**

- `main.bicep` compiles and lints clean with Bicep CLI 0.47.16 (`bicep build main.bicep --stdout`,
  `bicep lint main.bicep`: no errors, no warnings). The compiled template has the three NSGs, the VNet, and the
  NAT gateway, NAT IP and edge IP each behind their `condition`.
- The SSH-source logic, ported to Python, accepts 13 inputs that must pass and rejects 33 that must not
  (`*`, `Internet`, `0.0.0.0/0`, ranges, lists, leading zeros, octets above 255, whitespace, IPv6, and the
  blocked special-use ranges). The port mirrors the Bicep expression; Bicep itself cannot evaluate it offline.
- `Dockerfile.caddy` builds (Caddy v2.11.6 with `dns.providers.azure` v0.6.0), and `caddy list-modules` shows
  the module.
- `Caddyfile.wildcard`, whole, passes `caddy fmt`, `caddy adapt` and `caddy validate` on that image with the
  network disabled: wildcard and apex hosts, exactly one upstream `https://<APP_PRIVATE_IP>:<APP_PORT>`, `Host`
  kept as `{http.request.hostport}`, the staging CA by default and the override honoured, and the Azure
  provider configured with no credentials. It validates with the launch's hardening flags as well.
- **The launch procedure** was extracted from this README and run 102 times against a stand-in for `docker`
  that records every call and intercepts only the container start (the real `docker` served the image check and
  the `--network none` adapt check): accepted (LF, CRLF, no trailing newline, comments, optional port and CA)
  and refused (every variable missing, empty, duplicated, with whitespace or control characters or a BOM,
  quoted, unknown or mistyped names, malformed or non-private IPs, extra upstream, extra domain, bad ports,
  non-HTTPS or malformed CA URLs, bad subscription ids and resource groups, a missing env file, a missing
  image, the wrong directory). Every refusal exited nonzero and **never reached the container start**; no error
  message contained a value. Six deliberately tampered copies of the Caddyfile (second upstream, production CA
  as the default, no `Host` override, extra site address, no `subscription_id`) were each refused by the
  adapted-configuration check.
- **What that does not show:** that the launch works on a real edge host, or against a real Docker daemon's
  start. The stand-in replaced exactly that step.

**Read in Microsoft's documentation, not tested:** `defaultOutboundAccess` is creation-time only and exists in
API `2023-11-01`; new virtual networks on API versions released after 31 March 2026 default to private subnets;
Run Command uses the VM agent; Bastion needs `AzureBastionSubnet` (/26 or larger) and a Standard static public
IP except for the Developer and private-only SKUs.

**Not verified: Azure-side**
- Any deployment, `what-if` or `validate`; the template with the optional parameters switched on; how Azure
  treats the unset `defaultOutboundAccess` / `natGateway` (compiled to `null()`); the SSH-source expression as
  evaluated by Azure.
- Whether the data NSG's outbound internet deny interferes with platform traffic (DNS, the VM agent).
- Whether a redeploy with a changed NAT option fails, as the documentation implies.

**Not verified: runtime**
- Any certificate issuance (needs the DNS zone, the VM, its managed identity and the role assignment).
- The Caddy container running as a service, the managed-identity token from inside it, and the
  Caddy-to-nginx path.
- The multi-host application configuration, and the Trivy result for `Dockerfile.caddy`.

The ports and addresses were cross-checked against `nginx/default.conf.template`, `docker-compose.yml` and
the backend's tenant resolution (`TenantSubdomainResolver`).

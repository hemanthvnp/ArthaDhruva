// DEVELOPMENT-ONLY three-tier network for ArthaDhruva. Never deploy this to production.
//
// A public edge, a private application tier and a private data tier. The NAT gateway (stable egress
// address) and the edge public IP are both opt-in, because each bills by the hour whether or not anything
// is using it; see README.md for when a dev environment actually needs them.
//
//   Internet --80/443--> [snet-edge: Caddy] --443--> [snet-app: nginx + backend]
//                                                          |--5432/6379/7687--> [snet-data: Postgres, Redis, Neo4j]
//   snet-app --outbound--> NAT gateway (optional, one static public IP) --> Internet
//
// This creates the network only: no VM, managed identity, role assignment or NIC. It does not make the
// Caddy-to-nginx path work on its own (see "What this does not create" in README.md), and it is not applied
// to anything.
//
// Nothing in the template can refuse a resource group or a subscription, and resource-group naming is not a
// security boundary. Name both explicitly in every command, after confirming the subscription with
// `az account show` (README.md has the full, checked sequence). From this directory, into the dev group only:
//   az group create -n artha-dev-net-rg -l centralindia --tags environment=dev --subscription <dev-subscription-id>
//   az deployment group create -g artha-dev-net-rg --subscription <dev-subscription-id> -f main.bicep -p adminSourceIp=<your.ip>/32

@description('The environment this template builds. Development only: no other value is allowed.')
@allowed([ 'dev' ])
param envName string = 'dev'

@description('Region for every resource.')
param location string = resourceGroup().location

@description('Prefix for resource names; the environment is added after it, e.g. artha-dev-vnet.')
param prefix string = 'artha'

@description('Address space of the virtual network. Keep it inside 10.0.0.0/8: the repo\'s nginx only trusts X-Forwarded-For from 10/8, 172.16/12 and 192.168/16, so a public range would collapse every client onto the proxy address.')
param vnetCidr string = '10.20.0.0/16'

@description('Public edge subnet: the only one with a route in from the internet.')
param edgeCidr string = '10.20.1.0/24'

@description('Private application subnet.')
param appCidr string = '10.20.2.0/24'

@description('Private data subnet: no public IPs and no route to the internet at all.')
param dataCidr string = '10.20.3.0/24'

@description('The one operator address allowed to SSH to the edge host. Accepted only as a single IPv4 host in CIDR form, a.b.c.d/32: four decimal octets 0-255 without leading zeros, then /32. Anything else (empty, *, Internet, 0.0.0.0/0, a range, a list, a hostname, or an address in 0.0.0.0/8, 127.0.0.0/8, 169.254.0.0/16 or first octets 224-255) is ignored and no SSH rule is created: the template fails closed and does NOT reject the value, so check the sshIngressEnabled and sshAcceptedSource outputs. This is a syntax check plus those address blocks, not proof that the address is yours: private, shared and documentation addresses pass. Only reachable if createEdgePublicIp is true.')
param adminSourceIp string = ''

@description('Port nginx listens on in the application subnet: 443 in this repo (nginx/default.conf.template). Caddyfile.wildcard reads the same value as APP_PORT.')
param appPort int = 443

@description('Create the NAT gateway and its static public IP, and make the app subnet private. Off by default: it bills by the hour. Without it the app subnet keeps Azure\'s default outbound access (a Microsoft-owned address that can change, and is being retired for new networks). Decide before the first deployment: defaultOutboundAccess can only be set when a subnet is created, so changing this later can fail or need the subnet recreated, and switching it off in an incremental deployment does not delete the NAT gateway or its IP (they keep billing).')
param enableNatGateway bool = false

@description('Create a static public IP for the edge host. Off by default: it bills by the hour, and nothing in this template attaches it. Needed only to reach the dev edge from the internet.')
param createEdgePublicIp bool = false

var namePrefix = '${prefix}-${envName}'

// The SSH source must be a single IPv4 host as a.b.c.d/32. Bicep has no regular expressions, so the check is
// structural: exactly one '/', the suffix 32, and four dot-separated parts that each equal the canonical
// decimal text of 0..255 (which rules out '', '*', 'Internet', letters, leading zeros and lists). On top of
// that it refuses the address blocks that can never be an operator: 0.0.0.0/8, 127.0.0.0/8, 169.254.0.0/16
// (link-local, which includes the metadata service) and first octets 224-255 (multicast, reserved, broadcast).
// Everything else passes: private, shared (100.64/10), documentation and public addresses alike. It checks
// syntax and those blocks only; it cannot tell whether the address is the operator's. It never indexes into an
// array and never converts text to a number, so no input can make it error; it can only be false (fail
// closed). The expression is compile-checked, not evaluated against an Azure deployment.
var validOctets = map(range(0, 256), n => string(n))
var blockedFirstOctets = concat([ '0', '127' ], map(range(224, 32), n => string(n)))
var sshParts = split(adminSourceIp, '/')
var sshAddress = first(sshParts)
var sshAddressOctets = split(sshAddress, '.')
var sshSourceIsHost32 = length(sshParts) == 2 && last(sshParts) == '32' && length(sshAddressOctets) == 4 && length(filter(sshAddressOctets, o => contains(validOctets, o))) == 4 && !contains(blockedFirstOctets, first(sshAddressOctets)) && !startsWith(sshAddress, '169.254.')

var tags = {
  environment: envName
  purpose: 'dev-network'
}

var denyAllInbound = {
  name: 'deny-all-inbound'
  properties: {
    priority: 4000
    direction: 'Inbound'
    access: 'Deny'
    protocol: '*'
    sourceAddressPrefix: '*'
    sourcePortRange: '*'
    destinationAddressPrefix: '*'
    destinationPortRange: '*'
  }
}

// --- Network security groups: one per tier, each admitting only its upstream tier ------------------

resource edgeNsg 'Microsoft.Network/networkSecurityGroups@2023-11-01' = {
  name: '${namePrefix}-edge-nsg'
  location: location
  tags: tags
  properties: {
    securityRules: concat([
      {
        name: 'allow-https-http-from-internet'
        properties: {
          priority: 100
          direction: 'Inbound'
          access: 'Allow'
          protocol: 'Tcp'
          sourceAddressPrefix: 'Internet'
          sourcePortRange: '*'
          destinationAddressPrefix: '*'
          destinationPortRanges: [ '80', '443' ]
        }
      }
    ], sshSourceIsHost32 ? [
      {
        name: 'allow-ssh-from-operator'
        properties: {
          priority: 110
          direction: 'Inbound'
          access: 'Allow'
          protocol: 'Tcp'
          sourceAddressPrefix: adminSourceIp
          sourcePortRange: '*'
          destinationAddressPrefix: '*'
          destinationPortRange: '22'
        }
      }
    ] : [], [ denyAllInbound ])
  }
}

resource appNsg 'Microsoft.Network/networkSecurityGroups@2023-11-01' = {
  name: '${namePrefix}-app-nsg'
  location: location
  tags: tags
  properties: {
    securityRules: [
      {
        name: 'allow-app-port-from-edge'
        properties: {
          priority: 100
          direction: 'Inbound'
          access: 'Allow'
          protocol: 'Tcp'
          sourceAddressPrefix: edgeCidr
          sourcePortRange: '*'
          destinationAddressPrefix: '*'
          destinationPortRange: string(appPort)
        }
      }
      {
        name: 'allow-ssh-from-edge'
        properties: {
          priority: 110
          direction: 'Inbound'
          access: 'Allow'
          protocol: 'Tcp'
          sourceAddressPrefix: edgeCidr
          sourcePortRange: '*'
          destinationAddressPrefix: '*'
          destinationPortRange: '22'
        }
      }
      denyAllInbound
    ]
  }
}

resource dataNsg 'Microsoft.Network/networkSecurityGroups@2023-11-01' = {
  name: '${namePrefix}-data-nsg'
  location: location
  tags: tags
  properties: {
    securityRules: [
      {
        name: 'allow-databases-from-app'
        properties: {
          priority: 100
          direction: 'Inbound'
          access: 'Allow'
          protocol: 'Tcp'
          sourceAddressPrefix: appCidr
          sourcePortRange: '*'
          destinationAddressPrefix: '*'
          destinationPortRanges: [ '5432', '6379', '7687' ]
        }
      }
      denyAllInbound
      {
        // The data tier never needs the internet; a compromised database host cannot call out.
        name: 'deny-internet-outbound'
        properties: {
          priority: 4000
          direction: 'Outbound'
          access: 'Deny'
          protocol: '*'
          sourceAddressPrefix: '*'
          sourcePortRange: '*'
          destinationAddressPrefix: 'Internet'
          destinationPortRange: '*'
        }
      }
    ]
  }
}

// --- NAT gateway (optional): a stable outbound-only address for the application subnet ---------------

resource natIp 'Microsoft.Network/publicIPAddresses@2023-11-01' = if (enableNatGateway) {
  name: '${namePrefix}-nat-ip'
  location: location
  tags: tags
  sku: { name: 'Standard' }
  properties: { publicIPAllocationMethod: 'Static' }
}

resource nat 'Microsoft.Network/natGateways@2023-11-01' = if (enableNatGateway) {
  name: '${namePrefix}-nat'
  location: location
  tags: tags
  sku: { name: 'Standard' }
  properties: {
    idleTimeoutInMinutes: 10
    publicIpAddresses: [ { id: natIp.id } ]
  }
}

// --- Virtual network --------------------------------------------------------------------------------

resource vnet 'Microsoft.Network/virtualNetworks@2023-11-01' = {
  name: '${namePrefix}-vnet'
  location: location
  tags: tags
  properties: {
    addressSpace: { addressPrefixes: [ vnetCidr ] }
    subnets: [
      {
        name: 'snet-edge'
        properties: {
          addressPrefix: edgeCidr
          networkSecurityGroup: { id: edgeNsg.id }
        }
      }
      {
        // defaultOutboundAccess can only be set when a subnet is created. With the NAT gateway the subnet is
        // private and egress is explicit; without it the property is left unset (Azure's default outbound).
        name: 'snet-app'
        properties: {
          addressPrefix: appCidr
          networkSecurityGroup: { id: appNsg.id }
          natGateway: enableNatGateway ? { id: nat.id } : null
          defaultOutboundAccess: enableNatGateway ? false : null
        }
      }
      {
        name: 'snet-data'
        properties: {
          addressPrefix: dataCidr
          networkSecurityGroup: { id: dataNsg.id }
          defaultOutboundAccess: false
        }
      }
    ]
  }
}

// --- Edge public IP (optional): the address a dev wildcard DNS record would point at ----------------

resource edgeIp 'Microsoft.Network/publicIPAddresses@2023-11-01' = if (createEdgePublicIp) {
  name: '${namePrefix}-edge-ip'
  location: location
  tags: tags
  sku: { name: 'Standard' }
  properties: { publicIPAllocationMethod: 'Static' }
}

// What the template decided, not what Azure did. sshIngressEnabled is true only when adminSourceIp passed the
// check above, so the template asks Azure for the SSH rule; sshAcceptedSource echoes the value that passed
// (empty otherwise). Neither proves the rule exists in Azure, that a VM or public IP exists, that anything
// listens on port 22, or that the address is the operator's.
output sshIngressEnabled bool = sshSourceIsHost32
output sshAcceptedSource string = sshSourceIsHost32 ? adminSourceIp : ''
output edgePublicIp string = edgeIp.?properties.ipAddress ?? ''
output natEgressIp string = natIp.?properties.ipAddress ?? ''
output edgeSubnetId string = vnet.properties.subnets[0].id
output appSubnetId string = vnet.properties.subnets[1].id
output dataSubnetId string = vnet.properties.subnets[2].id

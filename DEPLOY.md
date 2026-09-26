# Deploying ArthaDhruva for free

A step-by-step path to a live, always-on, HTTPS deployment at no cost, on Oracle Cloud's Always Free
tier. Plan on 60 to 90 minutes the first time.

**What you end up with**

```
 Internet ──▶ Caddy :80/:443 ──▶ nginx ──▶ backend ──▶ Postgres (TLS), Redis, Neo4j
              Let's Encrypt      headers    Spring Boot   (private Docker network, no published ports)
              auto-renewing      rate limit
                                 static UI
```

- One VM running the whole Compose stack with the `production` profile: secrets come from Docker
  secrets, and the app refuses to start on a missing secret or a leftover dev default.
- A real certificate that renews itself.
- Push to `prod`, CI passes, the server redeploys itself (GitHub Actions over SSH).
- Nightly database backups.

**How much of this has been verified.** The compose overlay, Caddy, nginx real-IP handling,
`deploy/backup.sh`, the frontend build container and the fresh-install first login were all rehearsed
end to end on a laptop under the `production` profile (this caught and fixed a broken
`scripts/gen-secrets.sh`). The Oracle-specific steps (account, VM, firewall) and the SSH deploy job
have **not** been run against a real Oracle VM yet; treat your first run as the final test, and the
troubleshooting table at the bottom lists what usually goes wrong.

Free-tier terms change. Check Oracle's current Always Free limits before you rely on them.

---

## Part 1: Create the server

### 1. Oracle Cloud account
1. Sign up at <https://www.oracle.com/cloud/free/>. A card is required for identity verification; the
   Always Free resources are not charged.
2. Choose your **home region** carefully: it cannot be changed, and free ARM capacity varies by region.

### 2. Create the VM
Compute → Instances → **Create instance**:

| Setting | Value |
|---|---|
| Image | **Canonical Ubuntu 24.04** (choose the **aarch64 / ARM** build) |
| Shape | **VM.Standard.A1.Flex**, 2 OCPU, 12 GB RAM (the free allowance is up to 4 OCPU / 24 GB in total; the whole stack needs about 1 GB) |
| Networking | New VCN with defaults, **assign a public IPv4 address** |
| SSH keys | Generate a key pair and **download the private key**. You cannot get it again |
| Boot volume | 50 to 100 GB (the free allowance is 200 GB in total) |

If you see **"Out of capacity for shape VM.Standard.A1.Flex"**, retry later, try another availability
domain, or drop to 1 OCPU / 6 GB, which is still plenty. Note the **public IP** once it is running.

### 3. Open ports 80 and 443 (two separate layers)
Both must be opened, and forgetting the second one is the most common failure.

**a) Cloud firewall.** Networking → your VCN → Security Lists → Default → **Add ingress rules**:
source `0.0.0.0/0`, protocol TCP, destination ports `80` and `443` (two rules).

**b) The VM's own firewall.** Oracle's Ubuntu images ship iptables rules that reject new connections.
After you SSH in (next step):

```bash
sudo iptables -I INPUT 5 -p tcp --dport 80  -m conntrack --ctstate NEW -j ACCEPT
sudo iptables -I INPUT 5 -p tcp --dport 443 -m conntrack --ctstate NEW -j ACCEPT
sudo apt-get install -y iptables-persistent   # answer Yes to saving the current rules
sudo netfilter-persistent save
```

### 4. Log in
```bash
chmod 600 ~/Downloads/ssh-key-*.key
ssh -i ~/Downloads/ssh-key-*.key ubuntu@<PUBLIC_IP>
```

---

## Part 2: A free hostname

Caddy can only get a certificate for a name, not a bare IP. Pick one:

- **DuckDNS (recommended).** Sign in at <https://www.duckdns.org>, create a subdomain such as
  `artha-yourname`, and set its IP to your VM's public IP. Your hostname is
  `artha-yourname.duckdns.org`.
- **sslip.io (no signup).** Any name of the form `<anything>.<IP-with-dashes>.sslip.io` resolves to
  that IP, for example `app.203-0-113-10.sslip.io`. It shares Let's Encrypt rate limits with everyone
  using that domain, so issuance can occasionally fail; retry later or use DuckDNS.
- **A real domain** (about $10 a year) works the same way and is the only route to per-tenant
  subdomain login.

Check that it resolves before continuing: `nslookup artha-yourname.duckdns.org`.

---

## Part 3: Install and configure (on the VM)

### 5. Docker, plus swap as a safety net
```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER && newgrp docker
docker compose version          # should print v2 or later

sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

### 6. Get the code (the repository is public, so no credentials are needed)
```bash
git clone https://github.com/hemanthvnp/ArthaDhruva.git
cd ArthaDhruva
git checkout prod
```

### 7. Configure
```bash
cp deploy/.env.example .env
nano .env
```
Set at minimum `SITE_ADDRESS`, `APP_PUBLIC_URL` and `FRONTEND_URL` to your hostname (the URLs start
with `https://`). Everything else has a safe default. The `.env` is git-ignored. Database, JWT and
2FA credentials are not in it: `deploy.sh` generates them into `secrets/` on the first run and they
never leave the server.

Optional: set `ADMIN_PASSWORD` to choose the first admin's password. If you leave it empty, a random
one is printed once in the log (step 9).

---

## Part 4: First deploy

### 8. Run it
```bash
bash deploy/deploy.sh
```
It pulls `prod`, builds the frontend in a Node container, builds the backend image (the first ARM
build takes 5 to 10 minutes), starts the stack, waits for the backend to report healthy, and finally
checks `https://<your-host>/`. Caddy requests the certificate on the first request, which takes a few
seconds.

The script is idempotent. Run it again for every update.

### 9. First login
```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.deploy.yml \
  logs backend | grep -A3 "Bootstrap admin"
```
Open `https://<your-host>` and sign in with **organization `legacy`**, username **`admin`** and the
password from the log (or your `ADMIN_PASSWORD`). The first login sends you through **2FA
enrolment**: scan the QR code with an authenticator app. The password is shown only once.

---

## Part 5: Make it a real pipeline

### 10. Continuous deployment (push to `prod` redeploys)
On the VM, create a dedicated deploy key:
```bash
ssh-keygen -t ed25519 -f ~/.ssh/gha_deploy -N "" -C "github-actions-deploy"
cat ~/.ssh/gha_deploy.pub >> ~/.ssh/authorized_keys
cat ~/.ssh/gha_deploy          # the PRIVATE key: copy it for the secret below
ssh-keyscan -H <your-host-or-ip>   # copy the output for DEPLOY_KNOWN_HOSTS
```
In GitHub: repository → Settings → Secrets and variables → Actions → add:

| Secret | Value |
|---|---|
| `DEPLOY_HOST` | the VM's IP or hostname |
| `DEPLOY_USER` | `ubuntu` |
| `DEPLOY_SSH_KEY` | the private key you just printed |
| `DEPLOY_KNOWN_HOSTS` | the `ssh-keyscan` output (pins the server's host key) |

Test it from the Actions tab: **Deploy → Run workflow**. From then on, the flow is:

`feature branch → dev → staging → prod`. CI runs on each push, and the deploy job runs only after CI
passes on `prod`. Without the secrets the job skips itself.

### 11. Backups
```bash
mkdir -p ~/backups
( crontab -l 2>/dev/null; echo '0 3 * * * /home/ubuntu/ArthaDhruva/deploy/backup.sh >> /home/ubuntu/backups/backup.log 2>&1' ) | crontab -
bash deploy/backup.sh            # run once now to confirm
```
This keeps the newest 7 dumps on the VM. A backup on the same disk does not survive losing the VM, so
also copy them off-box (free options: `rclone` to a cloud bucket, or a periodic `scp` to your
laptop). **Restore:** `gunzip -c backup.sql.gz | docker compose ... exec -T postgres psql -U arthadhruva -d arthadhruva`
into an empty database.

### 12. Keep the free VM from being reclaimed
Oracle may reclaim Always Free compute instances it judges idle. A commonly used safeguard is
upgrading the account to **Pay As You Go**: you stay within the same free limits, and it removes the
reclamation risk. Set a budget alert (Billing → Budgets) at a few dollars so nothing surprises you.

---

## Day-2 operations

| Task | Command |
|---|---|
| Update to the latest `prod` | `bash deploy/deploy.sh` |
| Roll back | push a revert commit to `prod`; CI plus the deploy job apply it |
| Status | `docker compose -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.deploy.yml ps` |
| Backend log | same prefix, then `logs -f backend` |
| Scale the backend | add `--scale backend=2` to the `up` line in `deploy.sh` (one shared JWT secret is already in use) |

## Troubleshooting

| Symptom | Likely cause and fix |
|---|---|
| Browser cannot connect at all | One of the two firewall layers is closed (step 3), or `SITE_ADDRESS` does not resolve to the VM's IP |
| `deploy.sh` ends with "not answering yet" | DNS has not propagated, or ports 80/443 are blocked. Caddy needs port **80** too, for the certificate challenge. Check `logs caddy` |
| Certificate error from Let's Encrypt | Rate limit on a shared name such as sslip.io. Use DuckDNS or wait an hour |
| Backend stays unhealthy | `logs backend`. A message about "Development-default credential(s)" or "Missing required secret(s)" means `secrets/` is incomplete: run `sh scripts/gen-secrets.sh` |
| `secrets/SOMETHING` is a directory | The stack started while that secret file was missing, so Docker made a directory. `gen-secrets.sh` now removes empty ones; then re-run it |
| Out of memory | Confirm swap is on (`free -h`), and keep the observability profile off |
| Every user shares one rate limit | Something other than Caddy is proxying to nginx, so real client IPs are lost. Do not publish nginx's ports |

## For your resume

You can defend each of these decisions in an interview:

- **One TLS terminator with automatic renewal**, and nginx unpublished, so the only public surface is
  one container.
- **Secrets as Docker secrets with fail-fast startup** rather than environment variables and defaults.
- **Client IP recovery behind a proxy chain** that clients cannot spoof (verified with a forged
  `X-Forwarded-For` header).
- **A gated pipeline:** deploy only after CI is green on `prod`, host key pinned, no credentials in
  the repository.
- **A rehearsal of the production path locally** before touching a real server, which caught a broken
  secrets generator that would have failed the first deploy.
- **Cost:** about 1 GB of RAM in use, on a free VM.

## Tear down
```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.deploy.yml down -v
```
Then terminate the instance in the Oracle console and delete the DuckDNS entry.

# Roadmap: From CDK to Multi-Cloud GitOps (Cost-Optimized)

## Phase 0: The Current State (CDK & Fargate)
**Status:** Working deployment on AWS.

**Tech:** AWS CDK (TypeScript), Fargate (Public Subnet), RDS (Postgres), ElastiCache (Redis).

**Cost Check:** Fargate and ElastiCache are metered hourly.  
**Action:** Destroy these when not in use.

---

## Phase 1: The Terraform Transition (Vendor Agnostic IaC)
**Goal:** Replace CDK with Terraform to manage infrastructure as a "State" and prepare for Azure.

### 1.1 Remote State Management (The "Bank" Way)
*   **Action:** Create an S3 Bucket and a DynamoDB table. Configure Terraform to use these as a "Backend."
*   **Why:** This prevents state corruption and mimics how teams collaborate in a firm like FIS or SunLife.
*   **Free Tier Check:** S3 (5GB) and DynamoDB (25RCU/WCU) are part of the AWS Always Free tier.

### 1.2 Modularization
*   **Action:** Break the code into modules: `/modules/network`, `/modules/database`, `/modules/compute`.
*   **Strategy:** Write the modules such that you can swap the provider from `aws` to `azurerm` without changing the application logic.

### 1.3 Local Development with TFLint & Checkov
*   **Action:** Integrate local scanning tools to check for security misconfigurations before deploying.
*   **Free Tier Check:** These are open-source CLI tools. No cost.

---

## Phase 2: From Fargate to Kubernetes (The Enterprise OS)
**Goal:** Move away from proprietary "Serverless" containers to a standard Orchestrator.

### 2.1 The Cost-Conscious Alternative (K3s / Minikube)
*   **The Issue:** Managed Kubernetes (AWS EKS or Azure AKS) costs ~$73/month just for the control plane. This is NOT free-tier friendly.
*   **The Solution:** Use K3s or MicroK8s on a standard EC2 t3.micro (AWS Free Tier) or a B1s VM (Azure Free Tier).
*   **Action:** Deploy your microservices using Kubernetes Manifests (YAML) onto these lightweight nodes. This gives you 100% of the learning experience of EKS/AKS with $0 cost.

### 2.2 Helm for Package Management
*   **Action:** Create Helm Charts for your services.
*   **Why:** Banks use Helm to manage different versions of their apps across Dev, UAT, and Prod environments.

---

## Phase 3: The GitOps Pipeline (The Gold Standard)
**Goal:** Automate everything so that a "Git Push" is the only trigger for deployment.

### 3.1 GitHub Actions (CI)
*   **Action:** Build a pipeline that compiles your Java code, runs tests, builds Docker images, and pushes them to GitHub Container Registry (GHCR).
*   **Free Tier Check:** GitHub Actions is free for public repos and has a generous free allowance for private ones.

### 3.2 ArgoCD (CD)
*   **Action:** Install ArgoCD into your lightweight Kubernetes cluster.
*   **Strategy:** Set up "Auto-Sync" so that ArgoCD watches your GitHub repo. If you change a version number in a Helm chart, ArgoCD automatically pulls the new image into the cluster.
*   **Why:** This is the pinnacle of modern deployment. It provides a full audit trail and "self-healing" capabilities.

# 🛑 AWS Free Tier Corrections & Cost Optimization

The deployment failed because the AWS account is on the Free Tier, and the constructs used (Aurora Serverless, MSK Serverless, ElastiCache Serverless) are enterprise-tier services that violate Free Tier limits and cost hundreds of dollars a month.

Please rewrite `infrastructure/lib/database-stack.ts` with the following strict Free Tier limits:

### 1. Fix PostgreSQL (RDS Free Tier)
Remove `rds.DatabaseCluster` (Aurora). Replace it with a standard `rds.DatabaseInstance`:
* Engine: PostgreSQL (e.g., `rds.DatabaseInstanceEngine.postgres({ version: rds.PostgresEngineVersion.VER_15 })`)
* Instance Type: `t4g.micro` or `t3.micro` (e.g., `ec2.InstanceType.of(ec2.InstanceClass.T4G, ec2.InstanceSize.MICRO)`)
* Allocated Storage: 20 GB.
* Keep it in the `PRIVATE_ISOLATED` subnets and retain the secret generation.

### 2. Fix Redis (ElastiCache Free Tier)
Remove `elasticache.CfnServerlessCache`. Replace it with a standard `elasticache.CfnCacheCluster`:
* Node Type: `cache.t3.micro`
* NumCacheNodes: 1
* Engine: `redis`
* Create a `CfnSubnetGroup` using the `PRIVATE_ISOLATED` subnets and attach it to the cluster.

### 3. Remove Managed Kafka (Cost Avoidance)
Remove `msk.CfnServerlessCluster` and the `AwsCustomResource` lookup entirely. AWS MSK has no free tier and costs >$500/month.
* Remove the `kafkaSecurityGroup` and `kafkaBootstrapServers` exports.
* We will inject Kafka as a container in the `ComputeStack` later, or use an external free provider.

Update the exports and properties, and wait for my review.
#!/usr/bin/env node
import 'source-map-support/register';
import * as cdk from 'aws-cdk-lib';
import { NetworkStack } from '../lib/network-stack';
import { DatabaseStack } from '../lib/database-stack';
import { ComputeStack } from '../lib/compute-stack';

const app = new cdk.App();

// Use the default AWS account and region configured in your local AWS CLI
const env = {
  account: process.env.CDK_DEFAULT_ACCOUNT,
  region: process.env.CDK_DEFAULT_REGION
};

// 1. Deploy the Network first
const networkStack = new NetworkStack(app, 'WealthMgmtNetworkStack', { env });

// 2. Deploy Databases into the Network
const databaseStack = new DatabaseStack(app, 'WealthMgmtDatabaseStack', {
  vpc: networkStack.vpc,
  env,
});

// 3. Deploy Compute instances into the Network and connect them to Databases
new ComputeStack(app, 'WealthMgmtComputeStack', {
  vpc: networkStack.vpc,
  marketDataTable: databaseStack.marketDataTable,
  databaseSecret: databaseStack.databaseSecret,
  postgresJdbcUrl: databaseStack.postgresJdbcUrl,
  redisEndpointAddress: databaseStack.redisEndpointAddress,
  redisEndpointPort: databaseStack.redisEndpointPort,
  databaseSecurityGroup: databaseStack.databaseSecurityGroup,
  redisSecurityGroup: databaseStack.redisSecurityGroup,
  env,
});

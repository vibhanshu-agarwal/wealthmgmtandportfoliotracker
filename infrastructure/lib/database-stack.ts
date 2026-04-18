import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as rds from 'aws-cdk-lib/aws-rds';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as elasticache from 'aws-cdk-lib/aws-elasticache';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';

export interface DatabaseStackProps extends cdk.StackProps {
  readonly vpc: ec2.IVpc;
}

export class DatabaseStack extends cdk.Stack {
  public readonly databaseSecurityGroup: ec2.SecurityGroup;
  public readonly redisSecurityGroup: ec2.SecurityGroup;
  public readonly databaseSecret: secretsmanager.ISecret;
  public readonly postgresInstance: rds.DatabaseInstance;
  public readonly marketDataTable: dynamodb.Table;
  public readonly redisCache: elasticache.CfnCacheCluster;
  public readonly postgresJdbcUrl: string;
  public readonly redisEndpointAddress: string;
  public readonly redisEndpointPort: string;

  constructor(scope: Construct, id: string, props: DatabaseStackProps) {
    super(scope, id, props);

    this.databaseSecurityGroup = new ec2.SecurityGroup(this, 'PostgresSecurityGroup', {
      vpc: props.vpc,
      description: 'Security group for RDS PostgreSQL',
      allowAllOutbound: true,
    });
    this.databaseSecurityGroup.addIngressRule(
      ec2.Peer.ipv4(props.vpc.vpcCidrBlock),
      ec2.Port.tcp(5432),
      'Allow PostgreSQL from VPC',
    );

    this.redisSecurityGroup = new ec2.SecurityGroup(this, 'RedisSecurityGroup', {
      vpc: props.vpc,
      description: 'Security group for ElastiCache Redis',
      allowAllOutbound: true,
    });
    this.redisSecurityGroup.addIngressRule(
      ec2.Peer.ipv4(props.vpc.vpcCidrBlock),
      ec2.Port.tcp(6379),
      'Allow Redis from VPC',
    );

    const postgresAdminSecret = new secretsmanager.Secret(this, 'PostgresAdminSecret', {
      description: 'Master credentials for Wealth Management PostgreSQL',
      generateSecretString: {
        secretStringTemplate: JSON.stringify({ username: 'postgres' }),
        generateStringKey: 'password',
        excludePunctuation: true,
      },
    });

    this.postgresInstance = new rds.DatabaseInstance(this, 'WealthMgmtPostgresInstance', {
      engine: rds.DatabaseInstanceEngine.postgres({
        version: rds.PostgresEngineVersion.VER_15,
      }),
      instanceType: ec2.InstanceType.of(ec2.InstanceClass.T3, ec2.InstanceSize.MICRO),
      vpc: props.vpc,
      vpcSubnets: {
        subnetType: ec2.SubnetType.PRIVATE_ISOLATED,
      },
      securityGroups: [this.databaseSecurityGroup],
      credentials: rds.Credentials.fromSecret(postgresAdminSecret),
      databaseName: 'wealthmgmt',
      allocatedStorage: 20,
      maxAllocatedStorage: 20,
      multiAz: false,
      publiclyAccessible: false,
      storageEncrypted: true,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
      deletionProtection: false,
    });

    this.databaseSecret = postgresAdminSecret;

    this.marketDataTable = new dynamodb.Table(this, 'MarketDataTable', {
      tableName: 'wealthmgmt-market-data',
      partitionKey: {
        name: 'symbol',
        type: dynamodb.AttributeType.STRING,
      },
      sortKey: {
        name: 'timestamp',
        type: dynamodb.AttributeType.STRING,
      },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });

    const redisSubnetGroup = new elasticache.CfnSubnetGroup(this, 'RedisSubnetGroup', {
      cacheSubnetGroupName: 'wealthmgmt-redis-subnet-group',
      description: 'Subnet group for Redis cache',
      subnetIds: props.vpc.selectSubnets({
        subnetType: ec2.SubnetType.PRIVATE_ISOLATED,
      }).subnetIds,
    });

    this.redisCache = new elasticache.CfnCacheCluster(this, 'RedisCacheCluster', {
      clusterName: 'wealthmgmt-redis',
      engine: 'redis',
      cacheNodeType: 'cache.t3.micro',
      numCacheNodes: 1,
      cacheSubnetGroupName: redisSubnetGroup.ref,
      vpcSecurityGroupIds: [this.redisSecurityGroup.securityGroupId],
    });

    this.postgresJdbcUrl = `jdbc:postgresql://${this.postgresInstance.dbInstanceEndpointAddress}:${this.postgresInstance.dbInstanceEndpointPort}/wealthmgmt`;
    this.redisEndpointAddress = this.redisCache.attrRedisEndpointAddress;
    this.redisEndpointPort = this.redisCache.attrRedisEndpointPort;
  }
}

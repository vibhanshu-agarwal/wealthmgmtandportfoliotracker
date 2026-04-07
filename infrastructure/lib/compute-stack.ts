import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as elbv2 from 'aws-cdk-lib/aws-elasticloadbalancingv2';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';
import * as iam from 'aws-cdk-lib/aws-iam';

export interface ComputeStackProps extends cdk.StackProps {
  readonly vpc: ec2.IVpc;
  readonly marketDataTable: dynamodb.ITable;
  readonly databaseSecret: secretsmanager.ISecret;
  readonly postgresJdbcUrl: string;
  readonly redisEndpointAddress: string;
  readonly redisEndpointPort: string;
  readonly databaseSecurityGroup: ec2.ISecurityGroup;
  readonly redisSecurityGroup: ec2.ISecurityGroup;
}

export class ComputeStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: ComputeStackProps) {
    super(scope, id, props);

    const ecsCluster = new ecs.Cluster(this, 'WealthMgmtEcsCluster', {
      vpc: props.vpc,
      containerInsightsV2: ecs.ContainerInsights.ENABLED,
      defaultCloudMapNamespace: {
        name: 'wealthmgmt.local',
      },
    });

    const albSecurityGroup = new ec2.SecurityGroup(this, 'AlbSecurityGroup', {
      vpc: props.vpc,
      description: 'ALB security group for public HTTP traffic',
      allowAllOutbound: true,
    });
    albSecurityGroup.addIngressRule(ec2.Peer.anyIpv4(), ec2.Port.tcp(80), 'Allow internet HTTP');

    const apiGatewayServiceSecurityGroup = new ec2.SecurityGroup(this, 'ApiGatewayServiceSecurityGroup', {
      vpc: props.vpc,
      description: 'API Gateway service SG: no public inbound, only from ALB',
      allowAllOutbound: true,
    });

    const portfolioServiceSecurityGroup = new ec2.SecurityGroup(this, 'PortfolioServiceSecurityGroup', {
      vpc: props.vpc,
      description: 'Portfolio service SG: no inbound internet access',
      allowAllOutbound: true,
    });

    const marketDataServiceSecurityGroup = new ec2.SecurityGroup(this, 'MarketDataServiceSecurityGroup', {
      vpc: props.vpc,
      description: 'Market data service SG: no inbound internet access',
      allowAllOutbound: true,
    });

    apiGatewayServiceSecurityGroup.addIngressRule(
      albSecurityGroup,
      ec2.Port.tcp(8080),
      'Allow API traffic only from ALB',
    );

    const portfolioTaskDefinition = new ecs.FargateTaskDefinition(this, 'PortfolioTaskDef', {
      cpu: 256,
      memoryLimitMiB: 512,
    });
    portfolioTaskDefinition.executionRole?.addManagedPolicy(
      iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AmazonECSTaskExecutionRolePolicy'),
    );

    portfolioTaskDefinition.addContainer('PortfolioContainer', {
      // Tell CDK to look one folder up, find the portfolio-service Dockerfile, and build it!
      image: ecs.ContainerImage.fromAsset('../portfolio-service'),
      environment: {
        SPRING_PROFILES_ACTIVE: 'aws',
        SPRING_DATASOURCE_URL: props.postgresJdbcUrl,
      },
      secrets: {
        SPRING_DATASOURCE_USERNAME: ecs.Secret.fromSecretsManager(props.databaseSecret, 'username'),
        SPRING_DATASOURCE_PASSWORD: ecs.Secret.fromSecretsManager(props.databaseSecret, 'password'),
      },
      logging: ecs.LogDrivers.awsLogs({ streamPrefix: 'portfolio-service' }),
      portMappings: [{ containerPort: 8081 }],
    });

    const marketDataTaskDefinition = new ecs.FargateTaskDefinition(this, 'MarketDataTaskDef', {
      cpu: 256,
      memoryLimitMiB: 512,
    });
    marketDataTaskDefinition.executionRole?.addManagedPolicy(
      iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AmazonECSTaskExecutionRolePolicy'),
    );

    marketDataTaskDefinition.addContainer('MarketDataContainer', {
      image: ecs.ContainerImage.fromAsset('../market-data-service'),
      environment: {
        SPRING_PROFILES_ACTIVE: 'aws',
        MARKET_DATA_TABLE_NAME: props.marketDataTable.tableName,
      },
      logging: ecs.LogDrivers.awsLogs({ streamPrefix: 'market-data-service' }),
      portMappings: [{ containerPort: 8082 }],
    });

    const apiGatewayTaskDefinition = new ecs.FargateTaskDefinition(this, 'ApiGatewayTaskDef', {
      cpu: 256,
      memoryLimitMiB: 512,
    });
    apiGatewayTaskDefinition.executionRole?.addManagedPolicy(
      iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AmazonECSTaskExecutionRolePolicy'),
    );

    apiGatewayTaskDefinition.addContainer('ApiGatewayContainer', {
      image: ecs.ContainerImage.fromAsset('../api-gateway'),
      environment: {
        SPRING_PROFILES_ACTIVE: 'aws',
        REDIS_HOST: props.redisEndpointAddress,
        REDIS_PORT: props.redisEndpointPort,
      },
      logging: ecs.LogDrivers.awsLogs({ streamPrefix: 'api-gateway' }),
      portMappings: [{ containerPort: 8080 }],
    });

    props.databaseSecret.grantRead(portfolioTaskDefinition.taskRole);
    props.marketDataTable.grantReadWriteData(marketDataTaskDefinition.taskRole);

    const portfolioService = new ecs.FargateService(this, 'PortfolioService', {
      cluster: ecsCluster,
      taskDefinition: portfolioTaskDefinition,
      desiredCount: 1,
      assignPublicIp: true,
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
      securityGroups: [portfolioServiceSecurityGroup],
      cloudMapOptions: {
        name: 'portfolio-service',
      },
    });

    const marketDataService = new ecs.FargateService(this, 'MarketDataService', {
      cluster: ecsCluster,
      taskDefinition: marketDataTaskDefinition,
      desiredCount: 1,
      assignPublicIp: true,
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
      securityGroups: [marketDataServiceSecurityGroup],
      cloudMapOptions: {
        name: 'market-data-service',
      },
    });

    const apiGatewayService = new ecs.FargateService(this, 'ApiGatewayService', {
      cluster: ecsCluster,
      taskDefinition: apiGatewayTaskDefinition,
      desiredCount: 1,
      assignPublicIp: true,
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
      securityGroups: [apiGatewayServiceSecurityGroup],
    });

    const applicationLoadBalancer = new elbv2.ApplicationLoadBalancer(this, 'ApiGatewayAlb', {
      vpc: props.vpc,
      internetFacing: true,
      securityGroup: albSecurityGroup,
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
    });

    const listener = applicationLoadBalancer.addListener('HttpListener', {
      port: 80,
      open: false,
    });

    listener.addTargets('ApiGatewayTarget', {
      port: 8080,
      targets: [
        apiGatewayService.loadBalancerTarget({
          containerName: 'ApiGatewayContainer',
          containerPort: 8080,
        }),
      ],
      healthCheck: {
        path: '/actuator/health',
        healthyHttpCodes: '200-399',
      },
    });

    new cdk.CfnOutput(this, 'ApiGatewayAlbDnsName', {
      value: applicationLoadBalancer.loadBalancerDnsName,
    });

    // Keep references explicit so synth does not trim services as unused in future refactors.
    void portfolioService;
    void marketDataService;
  }
}

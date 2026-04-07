import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as ec2 from 'aws-cdk-lib/aws-ec2';

export class NetworkStack extends cdk.Stack {
  public readonly vpc: ec2.Vpc;

  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    // 1. VPC with Public (ALB/Fargate) and Isolated (Database) subnets
    this.vpc = new ec2.Vpc(this, 'WealthMgmtVpc', {
      maxAzs: 2,
      natGateways: 0, // Saves $32/month!
      subnetConfiguration: [
        {
          name: 'Public', // ALB and Fargate go here (Uses Free IGW for outbound)
          subnetType: ec2.SubnetType.PUBLIC,
          cidrMask: 24,
        },
        {
          name: 'Isolated', // Aurora Database goes here (No internet access)
          subnetType: ec2.SubnetType.PRIVATE_ISOLATED,
          cidrMask: 24,
        },
      ],
    });

    // 2. Gateway Endpoints are 100% FREE. This keeps S3 and DynamoDB traffic inside the AWS backbone.
    this.vpc.addGatewayEndpoint('S3GatewayEndpoint', {
      service: ec2.GatewayVpcEndpointAwsService.S3,
    });

    this.vpc.addGatewayEndpoint('DynamoDbGatewayEndpoint', {
      service: ec2.GatewayVpcEndpointAwsService.DYNAMODB,
    });

    // Note: We removed the expensive Interface Endpoints. Fargate will use the IGW instead.
  }
}
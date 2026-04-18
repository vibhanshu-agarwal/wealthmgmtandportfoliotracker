# 🛑 IAM Permission Fix: ECS Execution Role

Our ECS Fargate deployment failed with the following error:
`AccessDeniedException: User... is not authorized to perform: ecr:GetAuthorizationToken`

The Task Definitions are missing the standard ECS Execution Role managed policy required to pull images from ECR and push logs to CloudWatch.

### The Fix:
Please update `infrastructure/lib/compute-stack.ts`:
1. Ensure `aws-cdk-lib/aws-iam` is imported as `iam`.
2. For all three Task Definitions (`apiGatewayTaskDefinition`, `portfolioTaskDefinition`, and `marketDataTaskDefinition`), explicitly attach the Amazon managed policy for ECS task execution to their `executionRole`.

Add this exact logic for all three:
```typescript
taskDefinition.executionRole?.addManagedPolicy(
  iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AmazonECSTaskExecutionRolePolicy')
);
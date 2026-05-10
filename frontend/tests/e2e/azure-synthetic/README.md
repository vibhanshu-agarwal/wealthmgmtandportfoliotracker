# Azure Synthetic Monitoring - Local Debugging

This directory contains the synthetic monitoring suite designed to run against the live Azure environment (`https://wealthmgmt-azure-prod.azurewebsites.net`).

## Prerequisites

1. **Environment Variables**: You must set the following variables in your `.env` or terminal:
   ```bash
   export INTERNAL_API_KEY="your-secret-key"
   export E2E_TEST_USER_EMAIL="e2e-test-user@wealthmgmt-azure-prod.azurewebsites.net"
   export E2E_TEST_USER_PASSWORD="your-password"
   export BASE_URL="https://wealthmgmt-azure-prod.azurewebsites.net"
   export NEXT_PUBLIC_API_BASE_URL="https://api-wealthmgmt-azure-prod.azurewebsites.net"
   ```

2. **Dependencies**: Ensure you have installed the frontend dependencies:
   ```bash
   cd frontend
   npm install
   npx playwright install chromium
   ```

## Running Tests Locally (Headed Mode)

To visually debug issues like UI scaling with 160 assets or login failures, run the following command from the `frontend` directory:

```bash
npx playwright test --project=azure-synthetic --headed
```

### What happens during the run?
1. **Global Setup**: The `tests/e2e/global-setup.ts` script will fire three `POST` requests to the internal seeding endpoints. This resets the live database to the "Golden State" (160 assets).
2. **Serial Execution**: Tests run one by one (`workers: 1`) to avoid overloading the Container Apps or hitting concurrency limits.
3. **Timeouts**: A 30-second timeout is enforced per test. Azure Container Apps don't have cold starts like Lambda, but network latency and Azure Front Door CDN warming may add initial delays.

## Test Files
- `login.spec.ts`: Verifies production authentication.
- `live-contract.spec.ts`: Verifies the 160-asset portfolio renders correctly in the table.
- `ai-insights.spec.ts`: Verifies that Azure OpenAI can process the large dataset.
- `api-live-smoke.spec.ts`: API endpoint smoke tests.
- `azure-synthetic.spec.ts`: Main health check suite (login + dashboard).

## Troubleshooting UI Scaling
If the 160-row dataset causes layout issues, use the `--headed` mode and open the browser console to check for:
- CSS Grid/Flexbox overflows.
- Table rendering performance lags.
- Next.js hydration mismatches.
- Azure Front Door cache headers.

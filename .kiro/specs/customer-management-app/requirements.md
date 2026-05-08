# Requirements Document

## Introduction

A standalone Customer Management Application built as a tech evaluation exercise. The application demonstrates senior-level full-stack development practices including clean architecture, comprehensive testing, production-quality error handling, and iterative development methodology. The system provides robust customer creation and retrieval operations with a React frontend and Spring Boot backend, scoped to approximately 3-4 hours of development effort. The emphasis is on depth of implementation quality — thorough validation, comprehensive error handling, and extensive testing — rather than breadth of features.

## Glossary

- **Backend**: The Spring Boot 3.x REST API application running on Java 21 with an embedded H2 database
- **Frontend**: The React 19 + TypeScript single-page application that communicates with the Backend
- **Customer**: A business entity representing a person with attributes including first name, last name, and date of birth
- **Customer_List_View**: The Frontend page displaying all customers in a paginated, sortable table
- **Customer_Form**: The Frontend component for creating new customer records
- **Validation_Service**: The Backend component responsible for enforcing business rules on customer data
- **API**: The RESTful HTTP interface exposed by the Backend for customer operations
- **H2_Database**: The embedded relational database used for persistence in development and testing

## Requirements

### Requirement 1: Customer Creation

**User Story:** As an application user, I want to create new customer records, so that I can maintain a registry of customers.

#### Acceptance Criteria

1. WHEN a valid customer payload is submitted to the API, THE Backend SHALL persist the customer and return the created resource with HTTP 201 status and a `Location` header containing the URI of the newly created resource
2. WHEN a customer creation request is missing required fields (first name, last name, or date of birth), THE Validation_Service SHALL reject the request with HTTP 400 status and field-level error details
3. THE Validation_Service SHALL enforce that first name and last name are non-blank and do not exceed 100 characters
4. THE Validation_Service SHALL reject customer creation requests where the customer's age (calculated from date of birth to the current date) is less than 18 years, returning HTTP 400 status with a descriptive error message
5. THE Validation_Service SHALL reject customer creation requests where the date of birth is in the future, returning HTTP 400 status with a descriptive error message
6. WHEN a customer is successfully created, THE Backend SHALL assign a unique identifier and creation timestamp automatically
7. THE Backend SHALL NOT log date of birth at INFO level or below; PII fields MUST be masked in log output

### Requirement 2: Customer Retrieval

**User Story:** As an application user, I want to view customer records individually and as a list, so that I can look up customer information.

#### Acceptance Criteria

1. WHEN a GET request is made to the customers endpoint, THE API SHALL return a paginated list of customers with page metadata (page number, page size, total elements, total pages)
2. WHEN a GET request includes a search query parameter, THE API SHALL filter customers whose first name or last name contains the search term (case-insensitive)
3. WHEN a GET request includes sort parameters, THE API SHALL return results sorted by the specified field and direction
4. WHEN a GET request is made for a specific customer by identifier, THE API SHALL return the full customer resource
5. IF a GET request references a customer identifier that does not exist, THEN THE API SHALL return HTTP 404 status with a descriptive error message

### Requirement 3: Frontend Customer List View

**User Story:** As an application user, I want to see all customers in a table with pagination and search, so that I can efficiently browse and find customers.

#### Acceptance Criteria

1. THE Customer_List_View SHALL display customers in a table with columns for first name, last name, date of birth, and creation date
2. THE Customer_List_View SHALL provide pagination controls allowing navigation between pages
3. WHEN the user types in the search field, THE Customer_List_View SHALL filter the displayed customers after a 300ms debounce period
4. THE Customer_List_View SHALL provide a button to navigate to the Customer_Form for creating a new customer
5. IF the API returns an error during any list operation, THEN THE Frontend SHALL display a user-friendly error notification

### Requirement 4: Frontend Customer Form

**User Story:** As an application user, I want a form to create new customers, so that I can input customer data with validation feedback.

#### Acceptance Criteria

1. THE Customer_Form SHALL provide input fields for: first name, last name, and date of birth
2. THE Customer_Form SHALL perform client-side validation before submission and display inline error messages for invalid fields
3. WHEN the form is submitted with valid data, THE Frontend SHALL call the customer creation API endpoint and navigate back to the Customer_List_View on success
4. IF the API returns validation errors, THEN THE Customer_Form SHALL map server-side errors to the corresponding form fields
5. WHILE the form submission is in progress, THE Customer_Form SHALL disable the submit button and display a loading indicator

### Requirement 5: API Error Handling and Response Structure

**User Story:** As a developer consuming the API, I want consistent error responses following RFC 9457, so that I can handle errors programmatically.

#### Acceptance Criteria

1. THE API SHALL return error responses as `application/problem+json` conforming to RFC 9457 (Problem Details for HTTP APIs), containing: type, title, status, detail, and instance fields
2. WHEN validation errors occur, THE API SHALL include a `fieldErrors` extension array with field-specific error messages
3. IF an unexpected exception occurs, THEN THE Backend SHALL return HTTP 500 status with a generic error message and log the full exception with stack trace at ERROR level
4. THE Backend SHALL not expose stack traces, internal class names, or infrastructure details in API error responses
5. WHEN a request body cannot be parsed as valid JSON, THE Backend SHALL return HTTP 400 status with a message indicating malformed request body
6. THE Backend SHALL log 4xx errors at WARN level and 5xx errors at ERROR level with full stack trace

### Requirement 6: Testing Coverage

**User Story:** As a developer reviewing this codebase, I want comprehensive automated tests, so that I can verify correctness and maintainability.

#### Acceptance Criteria

1. THE Backend SHALL have unit tests covering service-layer business logic with mocked dependencies
2. THE Backend SHALL have integration tests verifying controller endpoints with the full Spring context and H2 database
3. THE Frontend SHALL have unit tests covering component rendering and user interaction behavior
4. THE combined test suite SHALL achieve a minimum of 70% code coverage across both Backend and Frontend

### Requirement 7: Documentation

**User Story:** As a reviewer evaluating this project, I want clear documentation, so that I can understand the design decisions and run the application.

#### Acceptance Criteria

1. THE project SHALL include a README.md containing: project overview, tech stack summary, prerequisites, setup instructions, how to run the application, how to run tests, and API endpoint documentation
2. THE project SHALL include an AI_USAGE.md file documenting which AI tools were used, how they were used, and what value they provided
3. THE README.md SHALL document key design decisions including architecture choices, testing strategy, and trade-offs made within the time constraint

### Requirement 8: Iterative Development Structure

**User Story:** As a reviewer evaluating development methodology, I want to see a clear commit history showing iterative development phases, so that I can assess how the application was built incrementally.

#### Acceptance Criteria

1. THE project commit history SHALL demonstrate distinct development phases: project setup, backend API implementation (creation and retrieval endpoints), frontend implementation, testing, and documentation
2. EACH commit SHALL represent a logical, working increment of the application (no broken intermediate states)
3. THE commit messages SHALL follow conventional commit format and clearly describe what was added or changed in each phase

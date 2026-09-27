# Reservation — High-Concurrency Appointment Scheduling Backend

A production-oriented appointment reservation backend built with **Java 21** and **Spring Boot 3.5**. The project focuses on reliable reservation processing under contention, using PostgreSQL for durable state and Redis for queueing, caching, status tracking, and rate-limiting support.

## Highlights

- JWT-based authentication and Spring Security
- Queue-based reservation processing for high-concurrency workloads
- Automatic selection of the nearest available time slot
- Reservation lifecycle tracking and cancellation
- Redis-backed request queue, status tracking, and caching
- Optimistic locking for concurrent reservation updates
- Retry handling and dead-letter processing for failed requests
- Configurable API rate limiting with Bucket4j
- Liquibase database migrations
- Prometheus/Micrometer metrics and Spring Boot Actuator health endpoints
- OpenAPI / Swagger documentation

## Tech Stack

| Area | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.5 |
| Web | Spring MVC |
| Security | Spring Security + JWT |
| Persistence | Spring Data JPA |
| Database | PostgreSQL |
| Cache / Queue | Redis |
| Migrations | Liquibase |
| Resilience | Spring Retry |
| Rate limiting | Bucket4j |
| Observability | Micrometer, Prometheus, Spring Boot Actuator |
| API documentation | Springdoc OpenAPI / Swagger UI |
| Build | Maven |

## Architecture

```text
Client
  |
  v
REST API
  |
  +--> Security / Rate Limiting
  |
  v
Reservation Services
  |
  +--> PostgreSQL
  |      Durable reservation state
  |
  +--> Redis
         Queueing, caching, status tracking
```

The application uses a layered Spring architecture with controllers, services, repositories, security components, and infrastructure adapters around PostgreSQL and Redis.

## Reservation Flow

A reservation request is accepted through the API and can be processed asynchronously through the Redis-backed queue.

```text
Reservation request
  -> authentication + validation
  -> queue / processing
  -> find nearest available slot
  -> persist reservation
  -> publish reservation status
```

Concurrency-sensitive updates use optimistic locking so conflicting writes can be detected instead of silently overwriting one another.

## Reliability & Concurrency

The project includes several mechanisms intended for production-style failure handling:

- optimistic locking for concurrent modifications
- automatic retry with backoff for transient failures
- dead-letter handling for requests that cannot be processed successfully
- reservation expiration management
- Redis TTL policies to limit stale transient state
- status tracking for asynchronous reservation processing

## Security

Security is implemented with Spring Security and JWT-based authentication.

The API also includes:

- request validation
- configurable token expiration
- configurable rate limiting
- HTTP 429 responses when configured rate limits are exceeded
- application logging suitable for operational and audit analysis

## Observability

The application exposes health and metrics through Spring Boot Actuator.

```text
GET /actuator/health
GET /actuator/metrics
```

Prometheus integration is provided through Micrometer.

Reservation-specific metrics documented by the project include:

```text
reservation.queue.length
reservation.dlq.length
reservation.queue.processed
reservation.queue.errors.*
```

## API Documentation

After starting the application, Swagger UI is available at:

```text
http://localhost:8080/swagger-ui/index.html
```

## Requirements

- JDK 21+
- Maven
- PostgreSQL 14+
- Redis 6+

## Configuration

Reservation behavior can be configured through `application.yml`.

```yaml
reservation:
  queue:
    batch-size: 50
    poll-interval-ms: 10
  status:
    expiry-hours: 24
  rate-limiting:
    enabled: true
  expiry:
    hours: 24
    check-minutes: 15
```

Adjust these values for the deployment environment and expected workload.

## Getting Started

Clone the repository:

```bash
git clone https://github.com/HoomanDevp/reservation.git
cd reservation
```

Start PostgreSQL and Redis using the repository's container configuration where applicable:

```bash
docker-compose up -d
```

Build the project:

```bash
./mvnw clean install
```

On Windows:

```cmd
mvnw.cmd clean install
```

Run the application:

```bash
./mvnw spring-boot:run
```

## Testing

Run the test suite with:

```bash
./mvnw test
```

A Postman collection is included for exercising authentication, reservation creation, status tracking, and cancellation flows.

## Project Structure

```text
config/       Application configuration
controller/   REST endpoints
 dto/          API request and response models
entity/       JPA entities
exception/    Application exceptions
filter/       Web filters, including rate limiting
repository/   Spring Data repositories
security/     JWT authentication and security configuration
service/      Reservation and queue-processing logic
```

## Core Components

### ReservationService

Contains the core reservation business logic, including slot selection, reservation creation, cancellation, and conflict handling.

### ReservationQueueService

Coordinates Redis-backed asynchronous reservation processing, status tracking, retries, and failed-request handling.

### RedisCleanupService

Manages expiration and cleanup of transient Redis state to reduce stale-key accumulation.

### RateLimitFilter

Applies configurable request throttling and returns HTTP `429 Too Many Requests` when the configured policy is exceeded.

## Design Goals

The project is intended to demonstrate a reservation backend that treats concurrency and operational failure as first-class concerns rather than only implementing the happy path.

The design emphasizes:

- explicit reservation state
- contention-aware persistence
- asynchronous workload handling
- retry and failure paths
- observable runtime behavior
- clear API boundaries

## License

Free To Use License (FTUL). See `LICENSE` for details.

## Author

**Hooman Yarahmadi**  
GitHub: [@HoomanDevp](https://github.com/HoomanDevp)

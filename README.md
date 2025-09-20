# Argus Log Processing Service
A simple, efficient log processing service for modern applications. Built with Spring Boot, it supports various log formats and provides powerful filtering and aggregation capabilities.

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
## Prerequisite

- Java 17 or higher
- Maven 3.8+
- Git

## Setup

1. Clone the repository:
```bash
git clone git@github.com:DineshKuppan/argus-log-processor.git
cd argus-log-processor
```

## Build

Compile and package the application:

Build the project using Maven:
```bash
mvn clean package -DskipTests
```
## Run

Start the Spring Boot service:
```bash
java -jar target/argus-log-processor-0.0.1-SNAPSHOT.jar
(or)
mvn clean spring-boot:run
```

**Note:** The application will start on `http://localhost:9000`.

## Features

### Logging

- The service generates and processes logs in various formats (JSON, Nginx, key-value).
- Logs are written to files in `src/main/resources/logs/`.
- Uses SLF4J for application logging.

### Filters

- Request filters can be implemented to intercept and log HTTP requests.
- Filters may add metadata, trace IDs, or perform authentication checks.

### Aggregation

- Aggregation endpoints compute statistics from logs:
    - `byLevel`: Counts logs by severity (INFO, ERROR, etc.).
    - `topEndpoints`: Lists most accessed API endpoints.
    - `errorRate`: Calculates the percentage of error logs.
    - `total`: Total number of log records.

### DataSeeder

- Utility class for generating synthetic log data for testing and development.
- Supports multiple log formats:
    - JSON logs (`app.json`)
    - Nginx-style logs (`nginx.log`)
    - Key-value logs (`custom.log`)
- Use `DataSeeder.generate*` methods to populate log files.

## Example

To generate test logs:
```java
DataSeeder.generate();
DataSeeder.generateNginxLogs("src/main/resources/logs/nginx.log");
DataSeeder.generateKeyValueLogs("src/main/resources/logs/custom.log");
DataSeeder.generateJsonLogs("src/main/resources/logs/app.json");
```

## Swagger API Endpoints

| Method | Endpoint                        | Description           |
|--------|---------------------------------|-----------------------|
| POST   | /api/v1/logs/filter             | Filter logs           |
| POST   | /api/v1/logs/export/json        | Export logs as JSON   |
| POST   | /api/v1/logs/export/csv         | Export logs as CSV    |
| POST   | /api/v1/logs/cli-summary        | CLI summary of logs   |
| POST   | /api/v1/logs/aggregate          | Aggregate logs        |
| POST   | /api/v1/logs/aggregateV2        | Aggregate logs (v2)   |
| GET    | /api/v1/logs/parse              | Parse logs            |


## License
This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)


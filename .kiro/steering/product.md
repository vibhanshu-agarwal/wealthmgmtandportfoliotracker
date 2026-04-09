# Product: Wealth Management & Portfolio Tracker

A personal finance platform for tracking investment portfolios and market data in real time.

## Core Capabilities

- Portfolio holdings management with live valuations
- Market price tracking and updates via event streaming
- AI-generated investment insights
- Single-page dashboard with charts, holdings table, and summary cards

## Users

Individual investors who want a unified view of their portfolio performance and market movements.

## Architecture Summary

The frontend communicates exclusively with the API Gateway, which routes to backend microservices. Market price updates flow through Kafka, allowing portfolio and insight services to react asynchronously. The system is designed to run locally via Docker Compose and deploy to AWS via CDK.

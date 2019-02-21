# Terminated Pod Cleaner
## Description
Cleans up pods stuck terminating after a set amount of time

## Configuration

- `NAMESPACES` The kubernetes namespaces to watch, default watches all namespaces
- `WAIT_DURATION` Time in seconds to wait until force killing pods in a terminating state, default 300 Seconds
- `REQUIRE_LABEL` Required labels to watch, default all labels

#### TODO ####
1. 
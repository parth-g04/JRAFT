.PHONY: build test chaos bench check-history

build:
	gradle build

test:
	gradle test

# Implemented by the deterministic simulator in Phase 2.
chaos:
	@echo "Chaos simulation is not implemented yet (Phase 2)."
	@exit 2

bench:
	gradle :bench:run

check-history:
	@echo "History checker is not implemented yet (Phase 2)."
	@exit 2

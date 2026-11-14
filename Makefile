.PHONY: build test chaos chaos-one bench check-history

build:
	gradle build

test:
	gradle test

chaos:
	gradle :sim:chaos -Pseed=1 -Pseeds=$(or $(SEEDS),200)

chaos-one:
	gradle :sim:chaos -Pseed=$(SEED) -Pseeds=1

bench:
	gradle :bench:run

check-history:
	cd tools/porcupine-check && go run . $(abspath $(FILE))

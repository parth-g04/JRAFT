.PHONY: build test chaos chaos-one shard-chaos shard-chaos-one bench check-history

GRADLE ?= ./gradlew

build:
	$(GRADLE) build

test:
	$(GRADLE) test

chaos:
	$(GRADLE) :sim:chaos -Pseed=1 -Pseeds=$(or $(SEEDS),200)

chaos-one:
	$(GRADLE) :sim:chaos -Pseed=$(SEED) -Pseeds=1

shard-chaos:
	$(GRADLE) :sim:shardChaos -Pseed=1 -Pseeds=$(or $(SEEDS),300)

shard-chaos-one:
	$(GRADLE) :sim:shardChaos -Pseed=$(SEED) -Pseeds=1

bench:
	$(GRADLE) :bench:storageBenchmark

check-history:
	cd tools/porcupine-check && go run . $(abspath $(FILE))

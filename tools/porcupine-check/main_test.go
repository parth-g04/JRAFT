package main

import (
	"testing"

	"github.com/anishathalye/porcupine"
)

func TestKVModelChecksPutAndGet(t *testing.T) {
	model := kvModel()
	operations := []porcupine.Operation{
		{ClientId: 0, Input: operationInput{Op: "put", Key: "a", Value: "x"}, Call: 1, Output: nil, Return: 2},
		{ClientId: 0, Input: operationInput{Op: "get", Key: "a"}, Call: 3, Output: "x", Return: 4},
	}
	if result := porcupine.CheckOperations(model, operations); !result {
		t.Fatal("expected put followed by matching get to be linearizable")
	}
}

func TestKVModelRejectsStaleGet(t *testing.T) {
	model := kvModel()
	operations := []porcupine.Operation{
		{ClientId: 0, Input: operationInput{Op: "put", Key: "a", Value: "x"}, Call: 1, Output: nil, Return: 2},
		{ClientId: 0, Input: operationInput{Op: "get", Key: "a"}, Call: 3, Output: "old", Return: 4},
	}
	if result := porcupine.CheckOperations(model, operations); result {
		t.Fatal("expected stale get to be illegal")
	}
}

package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"time"

	"github.com/anishathalye/porcupine"
)

type operationInput struct {
	Op    string `json:"op"`
	Key   string `json:"key"`
	Value string `json:"value"`
}

type historyRecord struct {
	ClientID int             `json:"clientId"`
	Op       string          `json:"op"`
	Key      string          `json:"key"`
	Value    string          `json:"value"`
	Output   json.RawMessage `json:"output"`
	Call     int64           `json:"call"`
	Return   int64           `json:"return"`
}

type cell struct {
	Value   string
	Present bool
}

func kvModel() porcupine.Model {
	return porcupine.Model{
		Init: func() interface{} { return cell{} },
		Step: func(state interface{}, input interface{}, output interface{}) (bool, interface{}) {
			current := state.(cell)
			request := input.(operationInput)
			var returned *string
			if output != nil {
				value := output.(string)
				returned = &value
			}
			switch request.Op {
			case "get":
				if current.Present != (returned != nil) || (current.Present && current.Value != *returned) {
					return false, current
				}
				return true, current
			case "put":
				if returned != nil {
					return false, current
				}
				return true, cell{Value: request.Value, Present: true}
			case "append":
				if returned != nil {
					return false, current
				}
				return true, cell{Value: current.Value + request.Value, Present: true}
			case "delete":
				if current.Present != (returned != nil) || (current.Present && current.Value != *returned) {
					return false, current
				}
				return true, cell{}
			default:
				return false, current
			}
		},
		Equal: func(a interface{}, b interface{}) bool { return a.(cell) == b.(cell) },
		Partition: func(history []porcupine.Operation) [][]porcupine.Operation {
			byKey := make(map[string][]porcupine.Operation)
			for _, operation := range history {
				input := operation.Input.(operationInput)
				byKey[input.Key] = append(byKey[input.Key], operation)
			}
			keys := make([]string, 0, len(byKey))
			for key := range byKey {
				keys = append(keys, key)
			}
			sort.Strings(keys)
			partitions := make([][]porcupine.Operation, 0, len(keys))
			for _, key := range keys {
				partitions = append(partitions, byKey[key])
			}
			return partitions
		},
		DescribeOperation: func(input interface{}, output interface{}) string {
			request := input.(operationInput)
			return fmt.Sprintf("%s(%q, %q) -> %v", request.Op, request.Key, request.Value, output)
		},
		DescribeState: func(state interface{}) string {
			value := state.(cell)
			if !value.Present {
				return "<missing>"
			}
			return fmt.Sprintf("%q", value.Value)
		},
	}
}

func readHistory(path string) ([]porcupine.Operation, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()

	var operations []porcupine.Operation
	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 4096), 4*1024*1024)
	lineNumber := 0
	for scanner.Scan() {
		lineNumber++
		var record historyRecord
		if err := json.Unmarshal(scanner.Bytes(), &record); err != nil {
			return nil, fmt.Errorf("line %d: %w", lineNumber, err)
		}
		var output interface{}
		if string(record.Output) != "null" {
			var value string
			if err := json.Unmarshal(record.Output, &value); err != nil {
				return nil, fmt.Errorf("line %d output must be a string or null: %w", lineNumber, err)
			}
			output = value
		}
		operations = append(operations, porcupine.Operation{
			ClientId: record.ClientID,
			Input:    operationInput{Op: record.Op, Key: record.Key, Value: record.Value},
			Call:     record.Call,
			Output:   output,
			Return:   record.Return,
		})
	}
	if err := scanner.Err(); err != nil {
		return nil, err
	}
	return operations, nil
}

func main() {
	outputPath := flag.String("html", "", "write a visualization to this file when the history is illegal")
	flag.Parse()
	if flag.NArg() != 1 {
		fmt.Fprintln(os.Stderr, "usage: porcupine-check [--html output.html] HISTORY.jsonl")
		os.Exit(2)
	}
	inputPath := flag.Arg(0)
	operations, err := readHistory(inputPath)
	if err != nil {
		fatal(err)
	}
	model := kvModel()
	result, info := porcupine.CheckOperationsVerbose(model, operations, 30*time.Second)
	switch result {
	case porcupine.Ok:
		fmt.Println("OK")
	case porcupine.Illegal:
		fmt.Println("ILLEGAL")
		path := *outputPath
		if path == "" {
			path = filepath.Join(filepath.Dir(inputPath), "illegal-history.html")
		}
		if err := porcupine.VisualizePath(model, info, path); err != nil {
			fatal(err)
		}
		fmt.Printf("Visualization: %s\n", path)
		os.Exit(1)
	case porcupine.Unknown:
		fmt.Println("UNKNOWN (checker timed out)")
		os.Exit(2)
	default:
		fatal(errors.New("unrecognized checker result"))
	}
}

func fatal(err error) {
	fmt.Fprintln(os.Stderr, err)
	os.Exit(2)
}

package main

import "fmt"

type Validation struct {
	Name string
}

func (v Validation) Compute(a, b int) int {
	var result string = a + b
	numbers := []int{a, b}
	total := 0
	for _, n := range numbers {
		total += n
	}
	fmt.Printf("Total: %d\n", total)
	return result
}

func (v Validation) Unused() {
	x := 42
	fmt.Println("hello")
}

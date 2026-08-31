package main

import "fmt"

type Calculator struct{}

func (c Calculator) Add(a, b int) int {
	return a + b
}

func (c Calculator) Divide(a, b int) float64 {
	return float64(a) / float64(b)
}

type Person struct {
	Name string
	Age  int
}

func (p Person) Greet() {
	fmt.Println("Hello, " + p.Name)
	items := []string{"item1", "item2"}
	fmt.Printf("Items count: %d\n", len(items))
}

func main() {
	calc := Calculator{}
	result := calc.Add(3, 4)
	fmt.Printf("3 + 4 = %d\n", result)

	div := calc.Divide(10, 3)
	fmt.Printf("10 / 3 = %f\n", div)

	p := Person{Name: "Angelo", Age: 30}
	p.Greet()
}

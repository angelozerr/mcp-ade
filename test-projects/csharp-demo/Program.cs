using System;
using System.Collections.Generic;

namespace CSharpDemo
{
    public class Calculator
    {
        public int Add(int a, int b)
        {
            return a + b;
        }

        public double Divide(int a, int b)
        {
            return (double)a / b;
        }
    }

    public class Person
    {
        public string Name { get; set; } = "";
        public int Age { get; set; }

        public void Greet()
        {
            Console.WriteLine("Hello, " + Name);
            List<string> items = new List<string>();
            items.Add("item1");
            items.Add("item2");
            Console.WriteLine("Items count: " + items.Count);
        }
    }

    class Program
    {
        static void Main(string[] args)
        {
            Calculator calc = new Calculator();
            int result = calc.Add(3, 4);
            Console.WriteLine("3 + 4 = " + result);

            double div = calc.Divide(10, 3);
            Console.WriteLine("10 / 3 = " + div);

            Person p = new Person();
            p.Name = "Angelo";
            p.Age = 30;
            p.Greet();
        }
    }
}

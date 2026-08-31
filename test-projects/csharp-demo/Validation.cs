using System;
using System.Collections.Generic;

namespace CSharpDemo
{
    public class Validation
    {
        public string Name { get; set; } = "";

        public int Compute(int a, int b) 
        {
            string result = a + b;
            List<int> numbers = new List<int>();
            numbers.Add(a);
            numbers.Add(b);
            int total = numbers.Sum();
            Console.WriteLine("Total: " + total);
            return result;
        }

        public void Unused()
        {
            int x;
            Console.WriteLine("hello");
        }
    }
}

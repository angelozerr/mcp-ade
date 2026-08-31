# frozen_string_literal: true

require_relative "calculator"
require_relative "person"

calc = Calculator.new
result = calc.add(3, 4)
puts "3 + 4 = #{result}"

div = calc.divide(10, 3)
puts "10 / 3 = #{div}"

p = Person.new("Angelo", 30)
p.greet

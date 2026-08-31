# frozen_string_literal: true

class Validation
  attr_accessor :name

  def compute(a, b)
    result = a + b
    numbers = [a, b]
    total = numbers.sum
    puts "Total: #{total}"
    result
  end

  def unused
    x = 42
    puts "hello"
  end
end

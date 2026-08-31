# frozen_string_literal: true

class Person
  attr_accessor :name, :age

  def initialize(name, age)
    @name = name
    @age = age
  end

  def greet
    puts "Hello, #{@name}"
    items = ["item1", "item2"]
    puts "Items count: #{items.length}"
  end
end

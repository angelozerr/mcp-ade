package com.example.model;

import java.util.List;
import java.util.ArrayList;

/**
 * Represents a user in the system.
 * A user has a name, age, email, and a list of roles.
 *
 * @author Angelo ZERR
 * @since 1.0
 */
public class User {

    private String name;
    private int age;
    private String email;
    private List<String> roles;

    /**
     * Creates a new User instance.
     *
     * @param name  the user's full name
     * @param age   the user's age in years
     * @param email the user's email address
     */
    public User(String name, int age, String email) {
        this.name = name;
        this.age = age;
        this.email = email;
        this.roles = new ArrayList<>();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getAge() {
        return age;
    }

    public void setAge(int age) {
        this.age = age;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public List<String> getRoles() {
        return roles;
    }

    /**
     * Adds a role to this user.
     *
     * @param role the role to add (e.g., "admin", "user", "moderator")
     * @throws IllegalArgumentException if role is null
     */
    public void addRole(String role) {
        this.roles.add(role);
    }

    /**
     * Checks whether this user is an adult (age &gt;= 18).
     *
     * @return {@code true} if the user is 18 or older, {@code false} otherwise
     */
    public boolean isAdult() {
        return age >= 18;
    }

    /**
     * Returns a display-friendly name including the email.
     *
     * @return the display name in format "name (email)"
     */
    public String getDisplayName() {
        return name + " (" + email + ")";
    }

    @Override
    public String toString() {
        return "User{name='" + name + "', age=" + age + ", email='" + email + "'}";
    }
}

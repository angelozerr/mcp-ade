package com.example.service;

import com.example.model.User;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.io.IOException;

/**
 * Service class for managing users.
 * Provides CRUD operations and search functionality.
 */
public class UserService {

    private List<User> users = new ArrayList<>();
    private static final int MAX_USERS = 100;

    /**
     * Adds a user to the system.
     *
     * @param user the user to add
     * @throws IllegalStateException if the maximum number of users has been reached
     */
    public void addUser(User user) {
        if (users.size() >= MAX_USERS) {
            throw new IllegalStateException("Max users reached");
        }
        users.add(user);
    }

    /**
     * Finds a user by their name.
     *
     * @param name the name to search for
     * @return an Optional containing the user if found, empty otherwise
     */
    public Optional<User> findByName(String name) {
        for (User user : users) {
            if (user.getName().equals(name)) {
                return Optional.of(user);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns all adult users (age &gt;= 18).
     *
     * @return a list of adult users
     */
    public List<User> findAdults() {
        List<User> adults = new ArrayList<>();
        for (User user : users) {
            if (user.isAdult()) {
                adults.add(user);
            }
        }
        return adults;
    }

    public int getUserCount() {
        return users.size();
    }

    public void removeUser(String name) {
        users.removeIf(user -> user.getName().equals(name));
    }

    /**
     * Searches users by query string and age range.
     *
     * @param query  the search query (matches name or email), null for no filter
     * @param minAge minimum age (inclusive)
     * @param maxAge maximum age (inclusive)
     * @return a list of matching users
     */
    public List<User> searchUsers(String query, int minAge, int maxAge) {
        List<User> results = new ArrayList<>();
        for (User user : users) {
            boolean matchesQuery = query == null || user.getName().contains(query) || user.getEmail().contains(query);
            boolean matchesAge = user.getAge() >= minAge && user.getAge() <= maxAge;
            if (matchesQuery && matchesAge) {
                results.add(user);
            }
        }
        return results;
    }

    /**
     * Loads users from a file.
     *
     * @param path the file path to load from
     * @throws IOException if the file cannot be read
     */
    public void loadUsersFromFile(String path) throws IOException {
        throw new IOException("Not implemented yet");
    }

    public void processUsers() {
        for (int i = 0; i < users.size(); i++) {
            User user = users.get(i);
            if (user.isAdult()) {
                if (user.getRoles().isEmpty()) {
                    user.addRole("default");
                } else {
                    for (String role : user.getRoles()) {
                        if ("admin".equals(role)) {
                            System.out.println("Admin found: " + user.getName());
                        }
                    }
                }
            }
        }
    }
}

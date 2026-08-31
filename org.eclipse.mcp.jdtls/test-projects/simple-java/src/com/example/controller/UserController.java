package com.example.controller;

import com.example.model.User;
import com.example.model.Admin;
import com.example.service.UserService;
import com.example.service.UserValidator;
import com.example.util.StringUtils;
import java.util.List;

public class UserController {

    private final UserService userService;
    private final UserValidator validator;

    public UserController() {
        this.userService = new UserService();
        this.validator = new UserValidator();
    }

    public boolean createUser(String name, String email, int age) {
        User user = new User(name, age, email);
        if (!validator.validate(user)) {
            return false;
        }
        userService.addUser(user);
        return true;
    }

    public User findUser(String name) {
        return userService.findByName(name).orElse(null);
    }

    public List<User> listAdults() {
        return userService.findAdults();
    }

    public void deleteUser(String name) {
        userService.removeUser(name);
    }

    public String getUserDisplay(String name) {
        User user = findUser(name);
        if (user == null) {
            return "User not found";
        }
        String displayName = user.getDisplayName();
        return StringUtils.truncate(displayName, 50);
    }

    public void processAllUsers() {
        userService.processUsers();
    }
}

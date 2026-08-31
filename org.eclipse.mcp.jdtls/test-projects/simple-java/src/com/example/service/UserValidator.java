package com.example.service;

import com.example.model.User;

public class UserValidator implements Validator<User> {

    @Override
    public boolean validate(User user) {
        if (user == null) {
            return false;
        }
        if (user.getName() == null || user.getName().isEmpty()) {
            return false;
        }
        if (user.getAge() < 0 || user.getAge() > 150) {
            return false;
        }
        if (user.getEmail() == null || !user.getEmail().contains("@")) {
            return false;
        }
        return true;
    }

    @Override
    public String getErrorMessage() {
        return "Invalid user data";
    }
}

package com.example;

import com.example.model.User;
import com.example.service.UserService;

public class UserServiceTest {

    public void testAddUser() {
        UserService service = new UserService();
        User user = new User("John", 25, "john@example.com");
        service.addUser(user);
        assert service.getUserCount() == 1;
    }

    public void testFindByName() {
        UserService service = new UserService();
        User user = new User("Jane", 30, "jane@example.com");
        service.addUser(user);
        assert service.findByName("Jane").isPresent();
        assert !service.findByName("Unknown").isPresent();
    }

    public void testFindAdults() {
        UserService service = new UserService();
        service.addUser(new User("Child", 10, "child@example.com"));
        service.addUser(new User("Adult", 25, "adult@example.com"));
        assert service.findAdults().size() == 1;
    }
}

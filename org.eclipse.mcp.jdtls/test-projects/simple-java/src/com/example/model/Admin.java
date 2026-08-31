package com.example.model;

import java.util.Date;

public class Admin extends User {

    private String department;
    private Date lastLogin;

    public Admin(String name, int age, String email, String department) {
        super(name, age, email);
        this.department = department;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public Date getLastLogin() {
        return lastLogin;
    }

    public void setLastLogin(Date lastLogin) {
        this.lastLogin = lastLogin;
    }

    @Override
    public String getDisplayName() {
        return "[ADMIN] " + getName() + " - " + department;
    }

    public boolean hasPermission(String permission) {
        return "admin".equals(permission) || "read".equals(permission);
    }
}

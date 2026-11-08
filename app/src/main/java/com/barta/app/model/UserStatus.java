package com.barta.app.model;

/** Realtime Database: status/{uid} = { online: true/false, lastSeen: server time in millis }. */
public class UserStatus {
    public boolean online;
    public long lastSeen;

    public UserStatus() { }   // required by Realtime Database
}

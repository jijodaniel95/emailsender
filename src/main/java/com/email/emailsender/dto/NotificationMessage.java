package com.email.emailsender.dto;

public class NotificationMessage {
    public NotificationMessage() {}
    private String fullName;
    private String sender;
    private String subject;
    private String message;

    public NotificationMessage(String fullName, String sender, String subject, String message) {
        this.fullName = fullName;
        this.sender = sender;
        this.subject = subject;
        this.message = message;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getFullName() {
        return fullName;
    }
    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    @Override
    public String toString() {
        return "NotificationMessage{" +
                "fullName='" + fullName + '\'' +
                ", sender='" + sender + '\'' +
                ", subject='" + subject + '\'' +
                ", message='" + message + '\'' +
                '}';
    }
}
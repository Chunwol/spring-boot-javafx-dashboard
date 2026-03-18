package com.example.backend.service.realtime;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WebSocketService {

    private final SimpMessagingTemplate messagingTemplate;

    public void sendPcStatusUpdate(Object pcStatus) {
        messagingTemplate.convertAndSend("/topic/pc-status", pcStatus);
    }

    public void sendClassroomUpdate(Object classroomData) {
        messagingTemplate.convertAndSend("/topic/classrooms", classroomData);
    }
}
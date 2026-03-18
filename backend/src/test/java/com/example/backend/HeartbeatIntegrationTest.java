package com.example.backend;

import com.example.backend.dto.heartbeat.HeartbeatDto;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.pc.PcMasterRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;

@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class HeartbeatIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PcMasterRepository pcMasterRepository;

    @Test
    public void testHeartbeatEndpoint() throws Exception {
        String url = "http://localhost:" + port + "/api/v1/heartbeat";
        HeartbeatDto dto = new HeartbeatDto();
        dto.setMacAddress("00:0A:95:9D:68:16");
        dto.setHostname("LAB-102-15");
        dto.setIpAddress("192.168.10.15");
        dto.setTraffic(List.of());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<HeartbeatDto> request = new HttpEntity<>(dto, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
        Assertions.assertTrue(response.getStatusCode().is2xxSuccessful(), "Expected 2xx response, got " + response.getStatusCode() + " (body: " + response.getBody() + ")");

        // check DB
        PcMaster pc = pcMasterRepository.findById(dto.getMacAddress()).orElse(null);
        Assertions.assertNotNull(pc);
        Assertions.assertEquals("LAB-102-15", pc.getPcName());
    }
}

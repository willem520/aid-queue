package com.willem.controller;

import com.willem.config.HttpTaskProperties;
import com.willem.service.BookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PatientController {

    private final BookingService bookingService;
    private final HttpTaskProperties taskProperties;

    @GetMapping("/patients")
    public String queryPatients() {
        var task = taskProperties.getTasks().stream()
                .filter(t -> t.isEnabled())
                .findFirst()
                .orElse(null);
        if (task == null) {
            return "无可用任务配置";
        }
        String result = bookingService.queryPatientList(task);
        return result != null ? result : "查询失败";
    }

    @GetMapping("/hospitals")
    public String queryHospitals() {
        var task = taskProperties.getTasks().stream()
                .filter(t -> t.isEnabled())
                .findFirst()
                .orElse(null);
        if (task == null) {
            return "无可用任务配置";
        }
        String result = bookingService.queryHospitalList(task);
        return result != null ? result : "查询失败";
    }

    @GetMapping("/doctors")
    public String queryDoctors() {
        var task = taskProperties.getTasks().stream()
                .filter(t -> t.isEnabled())
                .findFirst()
                .orElse(null);
        if (task == null) {
            return "无可用任务配置";
        }
        String result = bookingService.queryDoctorList(task);
        return result != null ? result : "查询失败";
    }

    @GetMapping("/probe")
    public String probeDoctor(@RequestParam String name) {
        var task = taskProperties.getTasks().stream()
                .filter(t -> t.isEnabled())
                .findFirst()
                .orElse(null);
        if (task == null) {
            return "无可用任务配置";
        }
        return bookingService.probeDoctorCode(task, name);
    }

    @GetMapping("/searchDr")
    public String searchDoctor(@RequestParam String name) {
        var task = taskProperties.getTasks().stream()
                .filter(t -> t.isEnabled())
                .findFirst()
                .orElse(null);
        if (task == null) {
            return "无可用任务配置";
        }
        return bookingService.callApi(task, "/med/dr/srchDr", java.util.Map.of("drName", name));
    }

    @GetMapping("/drDetl")
    public String doctorDetail(@RequestParam String name,
                               @RequestParam String drCode,
                               @RequestParam String fixmedinsId,
                               @RequestParam String fixmedinsCode) {
        var task = taskProperties.getTasks().stream()
                .filter(t -> t.isEnabled())
                .findFirst()
                .orElse(null);
        if (task == null) {
            return "无可用任务配置";
        }
        return bookingService.callApi(task, "/med/dr/srchDrDetl",
                java.util.Map.of("drName", name, "drCode", drCode,
                        "fixmedinsId", fixmedinsId, "fixmedinsCode", fixmedinsCode));
    }
}

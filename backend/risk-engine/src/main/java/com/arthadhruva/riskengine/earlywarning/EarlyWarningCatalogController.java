package com.arthadhruva.riskengine.earlywarning;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class EarlyWarningCatalogController {

    private final EarlyWarningCatalogService catalogService;

    public EarlyWarningCatalogController(EarlyWarningCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/early-warning-loans")
    public List<EarlyWarningCatalogEntry> loans() {
        return catalogService.all();
    }
}

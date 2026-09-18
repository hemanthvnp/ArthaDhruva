package com.arthadhruva.riskengine.trajectory;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class TrajectoryCatalogController {

    private final TrajectoryCatalogService catalogService;

    public TrajectoryCatalogController(TrajectoryCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/trajectory-loans")
    public List<TrajectoryCatalogEntry> loans() {
        return catalogService.all();
    }
}

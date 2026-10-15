package dev.conduit.server.product;

import dev.conduit.server.product.ProductDtos.ProductView;
import dev.conduit.server.product.ProductDtos.SaveProductRequest;
import dev.conduit.server.web.PageView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @GetMapping
    public PageView<ProductView> list(@RequestParam(defaultValue = "1") @Min(1) int page,
                                      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
        return service.list(page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductView create(@Valid @RequestBody SaveProductRequest request) {
        return service.create(request);
    }

    @GetMapping("/{productKey}")
    public ProductView get(@PathVariable String productKey) {
        return service.get(productKey);
    }

    @PutMapping("/{productKey}")
    public ProductView update(@PathVariable String productKey, @Valid @RequestBody SaveProductRequest request) {
        return service.update(productKey, request);
    }

    @DeleteMapping("/{productKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String productKey) {
        service.delete(productKey);
    }
}

package com.qualityos.production.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.product.dto.ProductDtos.ProductReference;
import com.qualityos.product.service.ProductService;
import com.qualityos.production.dto.ManufacturingDtos.ProductSettingsResponse;
import com.qualityos.production.dto.ManufacturingDtos.SettingsResponse;
import com.qualityos.production.dto.ManufacturingDtos.UpdateProductSettingsRequest;
import com.qualityos.production.dto.ManufacturingDtos.UpdateSettingsRequest;
import com.qualityos.production.entity.Bom;
import com.qualityos.production.entity.ManufacturingModel;
import com.qualityos.production.entity.ManufacturingSettings;
import com.qualityos.production.entity.ProductManufacturingSettings;
import com.qualityos.production.entity.RevisionedDefinition.Status;
import com.qualityos.production.repository.BomRepository;
import com.qualityos.production.repository.ManufacturingSettingsRepository;
import com.qualityos.production.repository.ProductManufacturingSettingsRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** How the organisation, and each product, is made. BOMs belong to discrete products and recipes to process ones. */
@Service
public class ManufacturingSettingsService {

    private static final String ENTITY = "ManufacturingSettings";
    private static final List<Status> LIVE = List.of(Status.DRAFT, Status.SUBMITTED, Status.APPROVED);

    private final ManufacturingSettingsRepository settings;
    private final ProductManufacturingSettingsRepository productSettings;
    private final BomRepository boms;
    private final ProductService products;
    private final AuditService audit;

    ManufacturingSettingsService(
            ManufacturingSettingsRepository settings,
            ProductManufacturingSettingsRepository productSettings,
            BomRepository boms,
            ProductService products,
            AuditService audit) {
        this.settings = settings;
        this.productSettings = productSettings;
        this.boms = boms;
        this.products = products;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public SettingsResponse get() {
        ManufacturingSettings current = current();
        return new SettingsResponse(current.getManufacturingModel(), current.isSeparateApprover());
    }

    /**
     * A product's own choice or anything already defined for it must still fit, so an organisation cannot drop the
     * model that some of its products use.
     */
    @Transactional
    public SettingsResponse update(UpdateSettingsRequest request) {
        ManufacturingSettings current = current();
        ManufacturingModel model = request.manufacturingModel();
        if (model != ManufacturingModel.HYBRID) {
            ManufacturingModel other = model == ManufacturingModel.DISCRETE
                    ? ManufacturingModel.PROCESS : ManufacturingModel.DISCRETE;
            if (productSettings.findAll().stream().anyMatch(p -> p.getManufacturingModel() == other)) {
                throw new BusinessRuleException("Some products are made by %s manufacturing. Change them first."
                        .formatted(other.name().toLowerCase()));
            }
            Bom.Kind otherKind = kindFor(other);
            if (boms.existsByKindAndStatusIn(otherKind, LIVE)) {
                throw new BusinessRuleException("Some products still have %s. Retire or discard them first."
                        .formatted(otherKind == Bom.Kind.BOM ? "BOMs" : "recipes"));
            }
        }
        Map<String, Object> before = Map.of("manufacturingModel", current.getManufacturingModel().name(),
                "separateApprover", current.isSeparateApprover());
        current.setManufacturingModel(model);
        current.setSeparateApprover(request.separateApprover());
        settings.save(current);
        audit.record("MANUFACTURING_SETTINGS_CHANGED", ENTITY, current.getId(), before,
                Map.of("manufacturingModel", model.name(), "separateApprover", request.separateApprover()));
        return get();
    }

    @Transactional(readOnly = true)
    public ProductSettingsResponse getProduct(UUID productId) {
        products.requireExists(productId);
        return productResponse(productId);
    }

    @Transactional
    public ProductSettingsResponse updateProduct(UUID productId, UpdateProductSettingsRequest request) {
        ProductReference product = products.requireActive(productId);
        ManufacturingModel model = request.manufacturingModel();
        if (model == ManufacturingModel.HYBRID) {
            throw new BusinessRuleException("A product is made either by discrete or by process manufacturing.");
        }
        ManufacturingModel organisation = current().getManufacturingModel();
        if (organisation != ManufacturingModel.HYBRID && organisation != model) {
            throw new BusinessRuleException("Your organisation uses %s manufacturing only. Make it hybrid first."
                    .formatted(organisation.name().toLowerCase()));
        }
        Bom.Kind otherKind = kindFor(model == ManufacturingModel.DISCRETE ? ManufacturingModel.PROCESS
                : ManufacturingModel.DISCRETE);
        if (boms.existsByProductIdAndKindAndStatusIn(productId, otherKind, LIVE)) {
            throw new BusinessRuleException("%s still has %s. Retire or discard it first.".formatted(
                    product.code(), otherKind == Bom.Kind.BOM ? "a BOM" : "a recipe"));
        }
        ProductManufacturingSettings row = productSettings.findByProductId(productId)
                .orElseGet(() -> new ProductManufacturingSettings(productId, model));
        String before = row.getVersion() == null ? null : row.getManufacturingModel().name();
        row.setManufacturingModel(model);
        productSettings.save(row);
        audit.record("PRODUCT_MANUFACTURING_CHANGED", "Product", productId,
                before == null ? null : Map.of("manufacturingModel", before), Map.of("manufacturingModel", model.name()));
        return productResponse(productId);
    }

    /** Refuses a BOM for a process product, or a recipe for a discrete one. */
    @Transactional(readOnly = true)
    public void requireMadeWith(UUID productId, Bom.Kind kind, String productCode) {
        ManufacturingModel model = productModel(productId);
        if (model == null) {
            throw new BusinessRuleException("Choose whether %s is made by discrete or process manufacturing first."
                    .formatted(productCode));
        }
        if (kindFor(model) != kind) {
            throw new BusinessRuleException("%s is made by %s manufacturing, so it takes %s, not %s.".formatted(
                    productCode, model.name().toLowerCase(), kindFor(model) == Bom.Kind.BOM ? "a BOM" : "a recipe",
                    kind == Bom.Kind.BOM ? "a BOM" : "a recipe"));
        }
    }

    @Transactional(readOnly = true)
    public boolean separateApprover() {
        return current().isSeparateApprover();
    }

    private ManufacturingModel productModel(UUID productId) {
        return productSettings.findByProductId(productId)
                .map(ProductManufacturingSettings::getManufacturingModel)
                .orElseGet(() -> {
                    ManufacturingModel organisation = current().getManufacturingModel();
                    return organisation == ManufacturingModel.HYBRID ? null : organisation;
                });
    }

    private ProductSettingsResponse productResponse(UUID productId) {
        boolean chosen = productSettings.findByProductId(productId).isPresent();
        return new ProductSettingsResponse(productId, productModel(productId), chosen);
    }

    private ManufacturingSettings current() {
        return settings.findFirstBy().orElseGet(ManufacturingSettings::new);
    }

    private static Bom.Kind kindFor(ManufacturingModel model) {
        return model == ManufacturingModel.PROCESS ? Bom.Kind.RECIPE : Bom.Kind.BOM;
    }
}

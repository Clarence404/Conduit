package dev.conduit.server.thingmodel;

import dev.conduit.server.thingmodel.ThingModelDtos.PropertyRequest;
import dev.conduit.server.web.ItemErrorsException.ItemError;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ThingModelImportTest {

    private static PropertyRequest p(String id, String type) {
        return new PropertyRequest(id, id, type, null, null, null, null, null, null);
    }

    private static Map<String, DataType> existing(int count) {
        Map<String, DataType> map = new HashMap<>();
        for (int i = 0; i < count; i++) {
            map.put("e" + i, DataType.DOUBLE);
        }
        return map;
    }

    @Test
    void validFileHasNoErrors() {
        assertThat(ThingModelImport.validate(List.of(p("Ua", "DOUBLE"), p("on", "BOOL")),
                Map.of("Ua", DataType.DOUBLE), ImportMode.MERGE)).isEmpty();
    }

    @Test
    void collectsAllErrorsWithIdentifiers() {
        List<PropertyRequest> items = new ArrayList<>();
        items.add(p("Ua", "INT"));        // type change
        items.add(p("1bad", "DOUBLE"));   // bad identifier
        items.add(p("Ib", "DOUBLE"));
        items.add(p("Ib", "DOUBLE"));     // duplicate
        items.add(null);

        List<ItemError> errors = ThingModelImport.validate(items, Map.of("Ua", DataType.DOUBLE), ImportMode.MERGE);

        assertThat(errors).extracting(ItemError::identifier).contains("Ua", "1bad", "Ib", null);
        assertThat(errors).filteredOn(e -> "Ua".equals(e.identifier()))
                .extracting(ItemError::message).anySatisfy(m -> assertThat(m).contains("数据类型不可修改"));
        assertThat(errors).filteredOn(e -> "Ib".equals(e.identifier()))
                .extracting(ItemError::message).anySatisfy(m -> assertThat(m).contains("重复"));
    }

    @Test
    void mergeCountsExistingPlusNewAgainstTheLimit() {
        assertThat(ThingModelImport.validate(List.of(p("e0", "DOUBLE"), p("n1", "DOUBLE")), existing(199),
                ImportMode.MERGE)).isEmpty();
        assertThat(ThingModelImport.validate(List.of(p("n1", "DOUBLE"), p("n2", "DOUBLE")), existing(199),
                ImportMode.MERGE)).extracting(ItemError::message).anySatisfy(m -> assertThat(m).contains("200"));
    }

    @Test
    void replaceCountsOnlyTheFile() {
        List<PropertyRequest> file = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            file.add(p("n" + i, "DOUBLE"));
        }
        assertThat(ThingModelImport.validate(file, existing(200), ImportMode.REPLACE)).isEmpty();
        file.add(p("n200", "DOUBLE"));
        assertThat(ThingModelImport.validate(file, Map.of(), ImportMode.REPLACE))
                .extracting(ItemError::message).anySatisfy(m -> assertThat(m).contains("200"));
    }
}

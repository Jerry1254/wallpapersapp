package com.qingjing.wallpaper.distribution;

import static org.assertj.core.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DistributionQueriesTest {
    @Test void rejectsInvalidDateFiltersAndPagination(){
        assertThatThrownBy(()->DistributionQueries.filter(Map.of("from","bad"),false)).hasMessageContaining("日期");
        assertThatThrownBy(()->DistributionQueries.filter(Map.of("status","draft"),false)).hasMessageContaining("筛选");
        assertThatThrownBy(()->DistributionQueries.filter(Map.of("arbitrary","value"),false)).hasMessageContaining("查询");
        assertThatThrownBy(()->DistributionQueries.filter(Map.of("from","2020-01-01T00:00:00Z","to","2026-01-01T00:00:00Z"),true)).hasMessageContaining("366");
        assertThatThrownBy(()->DistributionQueries.number("101",20,100)).hasMessageContaining("分页");
    }
    @Test void searchIsBoundAsLiteralAndDatesUseInclusiveExclusiveBounds(){
        var filter=DistributionQueries.filter(Map.of("keyword","%_'; DROP TABLE x;--","from","2026-10-01T00:00:00Z","to","2026-10-02T00:00:00Z"),false);
        assertThat(filter.where()).contains("created_at>=?","created_at<?","LOCATE(?,");
        assertThat(filter.where()).doesNotContain("DROP");assertThat(filter.args()).hasSize(3);
    }
}

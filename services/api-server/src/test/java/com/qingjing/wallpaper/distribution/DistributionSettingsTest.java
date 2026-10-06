package com.qingjing.wallpaper.distribution;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qingjing.wallpaper.shared.web.ApiException;
import org.junit.jupiter.api.Test;

class DistributionSettingsTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void acceptsChoicesForAllFourFormsAndLegacyRequests() {
        for(String platform:new String[]{"douyin","xhs"})for(String type:new String[]{"image","video"}) {
            DistributionService.validatePublicationSettings(platform,type,json.createObjectNode());
            ObjectNode post=json.createObjectNode().put("visibility","private").put("declaration","虚构演绎，仅供娱乐");
            if(platform.equals("xhs"))post.put("originality","not_original");
            if(platform.equals("douyin")&&type.equals("video"))post.put("downloadPermission","deny");
            DistributionService.validatePublicationSettings(platform,type,post);
        }
    }
    @Test void rejectsWrongPlatformWrongMediaAndMalformedSettings() {
        for(ObjectNode post:new ObjectNode[]{
            json.createObjectNode().put("visibility","draft"), json.createObjectNode().put("visibility",true),
            json.createObjectNode().putNull("declaration"),json.createObjectNode().put("declaration","内容由AI生成"),
            json.createObjectNode().put("downloadPermission",""),json.createObjectNode().put("originality","转载"),
            json.createObjectNode().put("saveAsDraft",true)}) {
            assertThatThrownBy(()->DistributionService.validatePublicationSettings("xhs","image",post)).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(()->DistributionService.validatePublicationSettings("douyin","image",json.createObjectNode().put("downloadPermission","deny"))).hasMessageContaining("抖音视频");
        assertThatThrownBy(()->DistributionService.validatePublicationSettings("douyin","video",json.createObjectNode().put("originality","original"))).hasMessageContaining("小红书");
    }
}

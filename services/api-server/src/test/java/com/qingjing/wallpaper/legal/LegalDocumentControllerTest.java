package com.qingjing.wallpaper.legal;

import static com.qingjing.wallpaper.legal.LegalDocumentDtos.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.qingjing.wallpaper.shared.web.ApiExceptionHandler;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LegalDocumentControllerTest {
    @Test void publicEndpointIsAnonymousNoStoreAndAllowsOnlyTheOfficialWebsiteOrigin() throws Exception {
        var service=mock(LegalDocumentService.class);
        var content=new Content("隐私政策","2026年10月9日","说明",List.of(new Section("重要提示","正文")));
        when(service.published()).thenReturn(new PublicDocuments("privacy:1|terms:1",List.of(
            new PublishedDocument("privacy",1,1,content,Instant.EPOCH),new PublishedDocument("terms",1,1,content,Instant.EPOCH))));
        var mvc=MockMvcBuilders.standaloneSetup(new LegalDocumentController(service)).build();
        mvc.perform(get("/api/v1/public/legal-documents").header("Origin","https://biguo66.top"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(header().string("Access-Control-Allow-Origin","https://biguo66.top"))
            .andExpect(jsonPath("$.items[0].revision").value(1)).andExpect(jsonPath("$.consentVersion").value("privacy:1|terms:1"));
        mvc.perform(get("/api/v1/public/legal-documents").header("Origin","https://untrusted.example"))
            .andExpect(status().isForbidden());
    }
    @Test void adminRequiresVersionAndRejectsBlankSectionsBeforeServiceWrites() throws Exception {
        var service=mock(LegalDocumentService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new AdminLegalDocumentController(service)).setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(post("/api/v1/admin/legal-documents/privacy/publish")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/admin/legal-documents/privacy").header("If-Match","\"1\"")
            .contentType("application/json").content("""
                {"content":{"title":"标题","effectiveDate":"日期","introduction":"说明","sections":[{"title":"节","body":"  "}]},"requiresReconsent":false}
                """)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}

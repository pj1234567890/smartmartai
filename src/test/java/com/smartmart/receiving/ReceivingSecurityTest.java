package com.smartmart.receiving;

import com.smartmart.security.JwtUtil;
import com.smartmart.user.entity.Role;
import com.smartmart.user.entity.User;
import com.smartmart.user.repository.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authorization: every receiving endpoint is manager-only. Never trust a manager id from the
 * request body - identity always comes from the JWT.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReceivingSecurityTest {

    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwtUtil;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired com.smartmart.inventory.repository.InventoryRepository inventories;

    private String shopperToken;
    private String managerToken;

    @BeforeAll
    void seedUsers() {
        User shopper = users.save(User.builder()
                .name("Sam Shopper").email("shopper-sec@test.local")
                .password(passwordEncoder.encode("pw")).role(Role.SHOPPER).build());
        User manager = users.save(User.builder()
                .name("Priya Manager").email("manager-sec@test.local")
                .password(passwordEncoder.encode("pw")).role(Role.MANAGER).build());
        shopperToken = jwtUtil.generateToken(shopper.getId(), shopper.getEmail(), shopper.getRole().name());
        managerToken = jwtUtil.generateToken(manager.getId(), manager.getEmail(), manager.getRole().name());
    }

    @AfterAll
    void cleanup() {
        users.deleteAll();
    }

    @Test
    void shopperCannotListCreateOrConfirmReceipts() throws Exception {
        for (var request : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[]{
                get("/api/receiving"),
                post("/api/receiving").contentType("application/json").content("{\"supplierName\":\"S\",\"invoiceNumber\":\"I\"}"),
                post("/api/receiving/1/confirm").contentType("application/json"),
                post("/api/receiving/1/lines/1/create-product").contentType("application/json")
                        .content("{\"name\":\"Blocked\",\"brand\":\"Blocked\",\"category\":\"Blocked\",\"price\":1}"),
                post("/api/receiving/1/cartons/scan").contentType("application/json").content("{\"code\":\"CTN-1\"}"),
                multipart("/api/receiving/1/invoice").file(new org.springframework.mock.web.MockMultipartFile(
                        "file", "invoice.pdf", "application/pdf", new byte[]{1, 2, 3}))
        }) {
            mvc.perform(request.header("Authorization", "Bearer " + shopperToken))
                    .andExpect(status().is4xxClientError());
        }
    }

    @Test
    void anonymousRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/receiving")).andExpect(status().is4xxClientError());
    }

    @Test
    void managerTokenIsAcceptedByTheFilterChain() throws Exception {
        // A manager header passes authentication; the endpoint exists (200 with empty list page).
        mvc.perform(get("/api/receiving").header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk());
    }

    @Test
    @Transactional
    void managerCanCreateAndAttachCatalogueProductToInvoiceLineWithoutAddingStock() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var receiptResponse = mvc.perform(post("/api/receiving")
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType("application/json")
                        .content("{\"supplierName\":\"Review supplier\"}"))
                .andExpect(status().isCreated()).andReturn();
        long receiptId = mapper.readTree(receiptResponse.getResponse().getContentAsString()).get("id").asLong();

        var lineResponse = mvc.perform(post("/api/receiving/" + receiptId + "/lines")
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType("application/json")
                        .content("{\"description\":\"New invoice product\",\"invoiceQuantity\":3,\"unitOfMeasure\":\"UNIT\",\"unitCost\":\"2.50\"}"))
                .andExpect(status().isCreated()).andReturn();
        long lineId = mapper.readTree(lineResponse.getResponse().getContentAsString()).get("id").asLong();

        var linked = mvc.perform(post("/api/receiving/" + receiptId + "/lines/" + lineId + "/create-product")
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType("application/json")
                        .content("{\"name\":\"New invoice product\",\"brand\":\"Review brand\",\"category\":\"Review category\",\"price\":\"4.25\"}"))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.productId").isNumber())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.productName").value("New invoice product"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.matchStatus").value("MANUAL"))
                .andReturn();

        long productId = mapper.readTree(linked.getResponse().getContentAsString()).get("productId").asLong();
        assertThat(inventories.findByProductId(productId)).isEmpty();
    }

    @Test
    void managerIdCannotBeSmuggledThroughTheRequestBody() {
        // Receipt creation returns the receipt of the JWT principal; a fake manager id in the
        // payload changes nothing. Verified at the service layer contract: ReceiptService.create
        // takes the manager from the authenticated principal only (ReceivingController),
        // so a CreateReceipt DTO has no manager field at all.
        var dto = new com.smartmart.receiving.dto.ReceiptRequests.CreateReceipt(
                "Amul", "INV-1", null, null, null);
        assertThat(dto.getClass().getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("managerId", "createdBy", "manager");
    }
}

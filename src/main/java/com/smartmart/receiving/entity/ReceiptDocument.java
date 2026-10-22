package com.smartmart.receiving.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/** Uploaded supplier invoice document (metadata + extraction state). Files live on disk, never in the database. */
@Entity
@Table(name = "receipt_documents", indexes = @Index(name = "idx_receipt_doc_receipt", columnList = "receipt_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReceiptDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receipt_id", nullable = false)
    private GoodsReceipt receipt;

    /** Original filename as uploaded, sanitised. */
    @Column(nullable = false, length = 255)
    private String originalFilename;

    @Column(nullable = false, length = 100)
    private String mimeType;

    @Column(nullable = false)
    private Long fileSize;

    /** Opaque storage reference (safe internal name); never a raw filesystem path. */
    @Column(nullable = false, length = 255)
    private String storageRef;

    /** IMAGE or PDF. */
    @Column(nullable = false, length = 20)
    private String sourceType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ProcessingStatus processingStatus;

    /** Extracted raw text, kept for auditing re-extraction (contains no secrets). */
    @Column(columnDefinition = "text")
    private String extractedText;

    /** Human-readable warnings about uncertain or missing fields. */
    @Column(columnDefinition = "text")
    private String warnings;

    @Column(columnDefinition = "text")
    private String error;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime uploadedAt;

    public enum ProcessingStatus { UPLOADED, PROCESSING, COMPLETED, FAILED }
}

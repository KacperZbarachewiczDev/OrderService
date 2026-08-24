package com.task.ing.orderaudit.adapter.out.persistence.repository;

import com.task.ing.orderaudit.adapter.out.persistence.entity.PaymentEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface PaymentJpaRepository extends JpaRepository<PaymentEntity, String> {

    Optional<PaymentEntity> findByOrderId(String orderId);

    @Modifying
    @Query(value = """
            insert into payments (payment_id, order_id, status, first_seen_at, updated_at)
            values (:paymentId, :orderId, 'UNKNOWN', :now, :now)
            on conflict (payment_id) do nothing
            """, nativeQuery = true)
    int reserve(
            @Param("paymentId") String paymentId,
            @Param("orderId") String orderId,
            @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentEntity p where p.paymentId = :paymentId")
    Optional<PaymentEntity> findForUpdate(@Param("paymentId") String paymentId);
}

package com.task.ing.orderaudit.adapter.out.persistence.repository;

import com.task.ing.orderaudit.adapter.out.persistence.entity.OrderEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderJpaRepository extends JpaRepository<OrderEntity, String> {

    @Modifying
    @Query(value = """
            insert into orders (order_id, status, first_seen_at, updated_at)
            values (:orderId, 'UNKNOWN', :now, :now)
            on conflict (order_id) do nothing
            """, nativeQuery = true)
    int reserve(@Param("orderId") String orderId, @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderEntity o where o.orderId = :orderId")
    Optional<OrderEntity> findForUpdate(@Param("orderId") String orderId);

    @Query("""
            select o.orderId from OrderEntity o
            where o.updatedAt > :from and o.updatedAt <= :to
              and (:after is null or o.orderId > :after)
            order by o.orderId
            """)
    List<String> findIdsUpdatedBetween(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("after") String after,
            Pageable pageable);
}

package com.peakbooking.booking.infrastructure.jpa.repository;

import com.peakbooking.booking.infrastructure.jpa.entity.ProductEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductJpaRepository extends JpaRepository<ProductEntity, Long> {
}

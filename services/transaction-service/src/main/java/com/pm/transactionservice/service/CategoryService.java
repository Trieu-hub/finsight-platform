package com.pm.transactionservice.service;

import com.pm.transactionservice.dto.CategoryResponse;
import com.pm.transactionservice.dto.CreateCategoryRequest;
import com.pm.transactionservice.dto.UpdateCategoryRequest;

import java.util.List;

public interface CategoryService {

    List<CategoryResponse> list();

    CategoryResponse create(CreateCategoryRequest request);

    CategoryResponse update(Long id, UpdateCategoryRequest request);

    void delete(Long id);
}

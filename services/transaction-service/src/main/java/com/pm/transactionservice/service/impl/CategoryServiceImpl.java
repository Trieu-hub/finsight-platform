package com.pm.transactionservice.service.impl;

import com.pm.transactionservice.dto.CategoryResponse;
import com.pm.transactionservice.dto.CreateCategoryRequest;
import com.pm.transactionservice.dto.UpdateCategoryRequest;
import com.pm.transactionservice.entity.Category;
import com.pm.transactionservice.exception.CategoryConflictException;
import com.pm.transactionservice.exception.CategoryResourceNotFoundException;
import com.pm.transactionservice.repository.CategoryRepository;
import com.pm.transactionservice.repository.TransactionRepository;
import com.pm.transactionservice.service.CategoryService;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CategoryServiceImpl implements CategoryService {

    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;

    public CategoryServiceImpl(CategoryRepository categoryRepository,
                               TransactionRepository transactionRepository) {
        this.categoryRepository = categoryRepository;
        this.transactionRepository = transactionRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryResponse> list() {
        return categoryRepository.findAll(Sort.by(Sort.Direction.ASC, "id")).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public CategoryResponse create(CreateCategoryRequest request) {
        Category category = Category.builder()
                .name(request.getName())
                .type(request.getType())
                .icon(request.getIcon())
                .color(request.getColor())
                .isSystem(false)
                .build();
        return toResponse(categoryRepository.save(category));
    }

    @Override
    @Transactional
    public CategoryResponse update(Long id, UpdateCategoryRequest request) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new CategoryResourceNotFoundException("Category " + id + " not found"));

        if (request.getName() != null) {
            category.setName(request.getName());
        }
        if (request.getType() != null) {
            category.setType(request.getType());
        }
        if (request.getIcon() != null) {
            category.setIcon(request.getIcon());
        }
        if (request.getColor() != null) {
            category.setColor(request.getColor());
        }
        // isSystem is never modified.
        return toResponse(categoryRepository.save(category));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new CategoryResourceNotFoundException("Category " + id + " not found"));

        if (category.isSystem()) {
            throw new CategoryConflictException("CATEGORY_PROTECTED",
                    "System categories cannot be deleted");
        }
        if (transactionRepository.existsByCategoryId(id)) {
            throw new CategoryConflictException("CATEGORY_IN_USE",
                    "Category is referenced by existing transactions");
        }
        categoryRepository.delete(category);
    }

    private CategoryResponse toResponse(Category c) {
        return CategoryResponse.builder()
                .id(c.getId())
                .name(c.getName())
                .type(c.getType())
                .icon(c.getIcon())
                .color(c.getColor())
                .isSystem(c.isSystem())
                .build();
    }
}

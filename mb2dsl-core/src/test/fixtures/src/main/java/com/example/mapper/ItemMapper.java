package com.example.mapper;

import com.example.entity.Item;
import java.util.List;

/**
 * Item mapper interface — used by mb2dsl for mode refinement.
 */
public interface ItemMapper {
    List<Item> findAll();
    long countItems();
}

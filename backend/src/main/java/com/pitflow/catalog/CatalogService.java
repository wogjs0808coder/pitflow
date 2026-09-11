package com.pitflow.catalog;

import com.pitflow.common.ApiException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CatalogService {
  private final ServiceItemRepository items;

  public CatalogService(ServiceItemRepository items) {
    this.items = items;
  }

  public List<ServiceView> list(boolean all) {
    return (all ? items.findAllByOrderByNameAsc() : items.findAllByActiveTrueOrderByNameAsc())
        .stream().map(ServiceView::from).toList();
  }

  @Transactional
  public ServiceView create(ServiceRequest r) {
    return ServiceView.from(items.saveAndFlush(new ServiceItem(r)));
  }

  @Transactional
  public ServiceView update(UUID id, ServiceRequest r) {
    var item =
        items
            .findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "정비 항목을 찾을 수 없습니다."));
    item.update(r);
    items.flush();
    return ServiceView.from(item);
  }
}

package com.pitflow.finance;

import java.math.BigDecimal;
import java.security.SecureRandom;
import org.springframework.stereotype.Component;

public interface InvestmentReturnGenerator {
  BigDecimal nextRate();
}

@Component
class RandomInvestmentReturnGenerator implements InvestmentReturnGenerator {
  private final SecureRandom random = new SecureRandom();

  @Override
  public BigDecimal nextRate() {
    return BigDecimal.valueOf(random.nextInt(801) - 300, 4);
  }
}

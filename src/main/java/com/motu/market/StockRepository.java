package com.motu.market;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockRepository extends JpaRepository<Stock, String> {

	// 이름은 부분 일치, 코드는 앞부분 일치로 찾는다
	@Query("""
			select s from Stock s
			where (:market is null or s.market = :market)
			  and (:query is null or s.name like concat('%', :query, '%') or s.code like concat(:query, '%'))
			order by s.name
			""")
	Page<Stock> search(@Param("query") String query, @Param("market") Market market, Pageable pageable);

}

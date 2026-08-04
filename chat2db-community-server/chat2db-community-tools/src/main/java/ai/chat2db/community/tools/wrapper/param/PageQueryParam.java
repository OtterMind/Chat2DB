package ai.chat2db.community.tools.wrapper.param;
import ai.chat2db.community.tools.enums.OrderByDirectionEnum;
import java.util.ArrayList;
import java.util.List;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.experimental.SuperBuilder;
import org.hibernate.validator.constraints.Range;
@Data
@SuperBuilder
@AllArgsConstructor
public class PageQueryParam {
    @NotNull(message = "Page number is required")
    @Min(value = 1, message = "Page number must be greater than 0")
    private Integer pageNo;
    @NotNull(message = "Page size is required")
    @Range(min = 1, max = 100000,
        message = "Page size must be between 1 and " + 100000 + ".")
    private Integer pageSize;
    private Boolean enableReturnCount;
    private List<OrderBy> orderByList;
    public PageQueryParam() {
        this.pageNo = 1;
        this.pageSize = 100;
        this.enableReturnCount = Boolean.FALSE;
    }
    public void queryAll() {
        this.pageNo = 1;
        this.pageSize = Integer.MAX_VALUE;
    }
    public void queryOne() {
        this.pageNo = 1;
        this.pageSize = 1;
    }
    public PageQueryParam orderBy(OrderBy orderBy) {
        orderByList = new ArrayList<>();
        return andOrderBy(orderBy);
    }
    public PageQueryParam orderBy(String orderConditionName, OrderByDirectionEnum direction) {
        return orderBy(new OrderBy(orderConditionName, direction));
    }
    public PageQueryParam orderBy(IOrderCondition orderCondition) {
        return orderBy(orderCondition == null ? null : orderCondition.getOrderBy());
    }
    public PageQueryParam andOrderBy(OrderBy orderBy) {
        if (orderByList == null) {
            orderByList = new ArrayList<>();
        }
        if (orderBy != null) {
            orderByList.add(orderBy);
        }
        return this;
    }
    public PageQueryParam andOrderBy(String orderConditionName, OrderByDirectionEnum direction) {
        return andOrderBy(new OrderBy(orderConditionName, direction));
    }
    public PageQueryParam andOrderBy(IOrderCondition orderCondition) {
        return andOrderBy(orderCondition == null ? null : orderCondition.getOrderBy());
    }
}

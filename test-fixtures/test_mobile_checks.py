def test_mobile_runtime():
    import requests
    assert requests.__version__
    assert sum([2, 3, 5]) == 10
